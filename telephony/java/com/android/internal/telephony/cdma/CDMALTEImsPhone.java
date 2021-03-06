/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 * Not a Contribution.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.cdma;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallDetails;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IccCardApplicationStatus;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.ims.RilImsPhone;
import com.android.internal.telephony.Connection.DisconnectCause;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.util.Log;

public class CDMALTEImsPhone extends CDMALTEPhone {
    private static final String LOG_TAG = "CDMALTEImsPhone";
    private RilImsPhone imsPhone;

    public CDMALTEImsPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        super(context, ci, notifier);
    }

    @Override
    public State getState() {
        return mCT.state;
    }

    @Override
    public void setState(Phone.State newState) {
        mCT.state = newState;
    }

    protected void init(Context context, PhoneNotifier notifier) {
        Log.d(LOG_TAG, "init()");

        mCM.setPhoneType(Phone.PHONE_TYPE_CDMA);
        mCT = new CdmaImsCallTracker(this);
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context, mCM, this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mDataConnectionTracker = new CdmaDataConnectionTracker (this);
        mRuimPhoneBookInterfaceManager = new RuimPhoneBookInterfaceManager(this);
        mSubInfo = new PhoneSubInfo(this);
        mEriManager = new EriManager(this, context, EriManager.ERI_FROM_XML);

        mCM.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(this, EVENT_RADIO_ON, null);
        mCM.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mSST.registerForNetworkAttached(this, EVENT_REGISTERED_TO_NETWORK, null);
        mCM.setEmergencyCallbackMode(this, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);
        mCM.registerForExitEmergencyCallbackMode(this, EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE,
                null);

        PowerManager pm
            = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,LOG_TAG);

        //Change the system setting
        SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                Integer.toString(Phone.PHONE_TYPE_CDMA));

        // This is needed to handle phone process crashes
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        mIsPhoneInEcmState = inEcm.equals("true");
        if (mIsPhoneInEcmState) {
            // Send a message which will invoke handleExitEmergencyCallbackMode
            mCM.exitEmergencyCallbackMode(obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
        }

        // get the string that specifies the carrier OTA Sp number
        mCarrierOtaSpNumSchema = SystemProperties.get(
                TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA,"");

        // Sets operator alpha property by retrieving from build-time system property
        String operatorAlpha = SystemProperties.get("ro.cdma.home.operator.alpha");
        setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, operatorAlpha);

        // Sets operator numeric property by retrieving from build-time system property
        String operatorNumeric = SystemProperties.get(PROPERTY_CDMA_HOME_OPERATOR_NUMERIC);
        setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, operatorNumeric);

        // Sets iso country property by retrieving from build-time system property
        setIsoCountryProperty(operatorNumeric);

        // Sets current entry in the telephony carrier table
        updateCurrentCarrierInProvider(operatorNumeric);

        // Notify voicemails.
        //updateVoiceMail();

        if (PhoneFactory.isCallOnImsEnabled()) {
            /*
             * Default phone is not registered yet. Post event to buy
             * time and register imsphone, after PhoneApp onCreate completes
             * registering default phone.
             */
            sendMessage(obtainMessage(EVENT_INIT_COMPLETE));
        }
    }

    @Override
    public void handleMessage (Message msg) {
        Log.d(LOG_TAG, "Received event:" + msg.what);
        switch (msg.what) {
            case EVENT_INIT_COMPLETE:
                /*
                 * Register imsPhone after CDMALTEIMSPhone is registered as defaultphone
                 * through PhoneApp OnCreate.
                 */
                createImsPhone();
                break;
            default:
                super.handleMessage(msg);
        }
    }

    public String getPhoneName() {
        return "CDMALTEIms";
    }

    public int getMaxConnectionsPerCall() {
        return CdmaImsCallTracker.MAX_CONNECTIONS_PER_CALL;
    }

    public int getMaxConnections() {
        return CdmaImsCallTracker.MAX_CONNECTIONS;
    }

    public Connection
    dial (String dialString) throws CallStateException {
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);
        Log.d(LOG_TAG, "dialString=" + newDialString);
        newDialString = PhoneNumberUtils.formatDialString(newDialString); // only for cdma
        Log.d(LOG_TAG, "formated dialString=" + newDialString);
        CallDetails calldetails = new CallDetails();
        calldetails.call_domain = CallDetails.RIL_CALL_DOMAIN_CS;
        return mCT.dial(newDialString, calldetails);
    }

    private void createImsPhone() {
        Log.d(LOG_TAG, "Creating RilImsPhone");
        if (imsPhone == null) {
            if (getCallTracker() != null) {
                imsPhone = new RilImsPhone(getContext(), mNotifier, getCallTracker(),
                        mCM);
                CallManager.getInstance().registerPhone(imsPhone);
            } else {
                Log.e(LOG_TAG, "Null call tracker!!! Unable to create RilImsPhone");
            }
        } else {
            Log.e(LOG_TAG, "ImsPhone present already");
        }
    }

    private void destroyImsPhone() {
        if (imsPhone != null) {
            CallManager.getInstance().unregisterPhone(imsPhone);
            imsPhone.dispose();
        }
        imsPhone = null;
    }

    @Override
    public void dispose() {
        mCM.unregisterForImsNetworkStateChanged(this);
        destroyImsPhone();
        super.dispose();
    }

    public DisconnectCause
    disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes to local tones
         */

        int serviceState = getServiceState().getState();
        if (serviceState == ServiceState.STATE_POWER_OFF) {
            return DisconnectCause.POWER_OFF;
        } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                || serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
            return DisconnectCause.OUT_OF_SERVICE;
        } else if (mCdmaSubscriptionSource ==
                CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM
                && (getUiccApplication() == null ||
                getUiccApplication().getState() !=
                IccCardApplicationStatus.AppState.APPSTATE_READY)) {
            return DisconnectCause.ICC_ERROR;
        } else if (causeCode == CallFailCause.NORMAL_CLEARING) {
            return DisconnectCause.NORMAL;
        } else {
            return DisconnectCause.ERROR_UNSPECIFIED;
        }
    }
}