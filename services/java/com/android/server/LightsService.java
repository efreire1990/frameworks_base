/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IHardwareService;
import android.os.ILightService;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Message;
import android.provider.Settings;
import android.util.Slog;
import static android.provider.Settings.System.BUTTON_LIGHT_BRIGHTNESS;
import static android.provider.Settings.System.BUTTON_LIGHT_ENABLE;

import java.io.FileInputStream;
import java.io.FileOutputStream;

public class LightsService  extends ILightService.Stub {
    private static final String TAG = "LightsService";
    private static final boolean DEBUG = false;

    static final int LIGHT_ID_BACKLIGHT = 0;
    static final int LIGHT_ID_KEYBOARD = 1;
    static final int LIGHT_ID_BUTTONS = 2;
    static final int LIGHT_ID_BATTERY = 3;
    static final int LIGHT_ID_NOTIFICATIONS = 4;
    static final int LIGHT_ID_ATTENTION = 5;
    static final int LIGHT_ID_BLUETOOTH = 6;
    static final int LIGHT_ID_WIFI = 7;
    static final int LIGHT_ID_COUNT = 8;

    static final int LIGHT_FLASH_NONE = 0;
    static final int LIGHT_FLASH_TIMED = 1;
    static final int LIGHT_FLASH_HARDWARE = 2;

    /**
     * Light brightness is managed by a user setting.
     */
    static final int BRIGHTNESS_MODE_USER = 0;

    /**
     * Light brightness is managed by a light sensor.
     */
    static final int BRIGHTNESS_MODE_SENSOR = 1;

    private final Light mLights[] = new Light[LIGHT_ID_COUNT];

    public final String PERSIST_BUTTON_LIGHT_TIMEOUT = "persist.sys.timeout.light.but";
    private final String HW_PLATFORM = "ro.hw_platform";
    private final String HW_PLATFORM_SKU5 = "msm8x25_sku5";
    private boolean mButtonLightExist = false;
    public final int BUTTON_LIGHT_TIMEOUT_DEFAULT = 1500;
    private int mButtonLightTimeout = BUTTON_LIGHT_TIMEOUT_DEFAULT;
    private final int MSG_BUTTON_LIGHT_TIMEOUT = 1;
    private Handler mLightHandler = null;
    private static final Uri BUTTON_LIGHT_ENABLE_URI =
            Settings.System.getUriFor(BUTTON_LIGHT_ENABLE);
    private static final Uri BUTTON_LIGHT_BRIGHTNESS_URI =
            Settings.System.getUriFor(BUTTON_LIGHT_BRIGHTNESS);
    private boolean mButtonLightEnable = true;
    private static final int DEF_BUTTON_LIGHT_BRIGHTNESS = 2;
    private int mButtonBrightness = DEF_BUTTON_LIGHT_BRIGHTNESS;


    public boolean buttonLightExist() {
        return mButtonLightExist;
    }

    public void turnOnButtonLight() {
        setBrightnessButtonLight(mButtonBrightness);
    }

    public void setBrightnessButtonLight(int brightness) {
        if(buttonLightExist()) {
            if(mButtonLightEnable) {
                getLight(LIGHT_ID_BUTTONS).setBrightness(brightness);
            } else {
                getLight(LIGHT_ID_BUTTONS).setBrightness(0);
            }
        }
    }

    public void turnOffButtonLight() {
        setBrightnessButtonLight(0);
    }

    public void turnOnButtonLightOneShot() {
        setBrightnessButtonLightOneShot(mButtonBrightness);
    }

    public void setBrightnessButtonLightOneShot(int brightness) {
        if(mButtonLightEnable && brightness > 0) {
            mLightHandler.removeMessages(MSG_BUTTON_LIGHT_TIMEOUT);
            setBrightnessButtonLight(brightness);
            mLightHandler.sendMessageDelayed(
                    mLightHandler.obtainMessage(MSG_BUTTON_LIGHT_TIMEOUT),
                    mButtonLightTimeout);
        } else {
            turnOffButtonLight();
        }
    }

    public final class Light {

        private Light(int id) {
            mId = id;
        }

        public void setBrightness(int brightness) {
            setBrightness(brightness, BRIGHTNESS_MODE_USER);
        }

        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                int color = brightness & 0x000000ff;
                color = 0xff000000 | (color << 16) | (color << 8) | color;
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
            }
        }

        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, BRIGHTNESS_MODE_USER);
            }
        }


        public void pulse() {
            pulse(0x00ffffff, 7);
        }

        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (mColor == 0 && !mFlashing) {
                    setLightLocked(color, LIGHT_FLASH_HARDWARE, onMS, 1000, BRIGHTNESS_MODE_USER);
                    mH.sendMessageDelayed(Message.obtain(mH, 1, this), onMS);
                }
            }
        }

        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        private void stopFlashing() {
            synchronized (this) {
                setLightLocked(mColor, LIGHT_FLASH_NONE, 0, 0, BRIGHTNESS_MODE_USER);
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (color != mColor || mode != mMode || onMS != mOnMS || offMS != mOffMS) {
                if (DEBUG) Slog.v(TAG, "setLight #" + mId + ": color=#"
                        + Integer.toHexString(color));
                mColor = color;
                mMode = mode;
                mOnMS = onMS;
                mOffMS = offMS;
                setLight_native(mNativePointer, mId, color, mode, onMS, offMS, brightnessMode);
            }
        }

        private int mId;
        private int mColor;
        private int mMode;
        private int mOnMS;
        private int mOffMS;
        private boolean mFlashing;
    }

    /* This class implements an obsolete API that was removed after eclair and re-added during the
     * final moments of the froyo release to support flashlight apps that had been using the private
     * IHardwareService API. This is expected to go away in the next release.
     */
    private final IHardwareService.Stub mLegacyFlashlightHack = new IHardwareService.Stub() {

        private static final String FLASHLIGHT_FILE = "/sys/class/leds/spotlight/brightness";

        public boolean getFlashlightEnabled() {
            try {
                FileInputStream fis = new FileInputStream(FLASHLIGHT_FILE);
                int result = fis.read();
                fis.close();
                return (result != '0');
            } catch (Exception e) {
                return false;
            }
        }

        public void setFlashlightEnabled(boolean on) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.FLASHLIGHT)
                    != PackageManager.PERMISSION_GRANTED &&
                    mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires FLASHLIGHT or HARDWARE_TEST permission");
            }
            try {
                FileOutputStream fos = new FileOutputStream(FLASHLIGHT_FILE);
                byte[] bytes = new byte[2];
                bytes[0] = (byte)(on ? '1' : '0');
                bytes[1] = '\n';
                fos.write(bytes);
                fos.close();
            } catch (Exception e) {
                // fail silently
            }
        }
    };

    LightsService(Context context) {

        mNativePointer = init_native();
        mContext = context;
        mButtonLightTimeout = SystemProperties.getInt(PERSIST_BUTTON_LIGHT_TIMEOUT,
                BUTTON_LIGHT_TIMEOUT_DEFAULT);

        mLightHandler = new Handler() {
            public void handleMessage(Message msg) {
                synchronized(this) {
                    switch(msg.what) {
                    case MSG_BUTTON_LIGHT_TIMEOUT:
                        turnOffButtonLight();
                        break;
                    }
                }
            }
        };

        String hw_platform = SystemProperties.get(HW_PLATFORM);
        if(hw_platform != null && hw_platform.equals(HW_PLATFORM_SKU5)) {
            mButtonLightExist = true;
        } else {
            mButtonLightExist = false;
        }

        mContext.getContentResolver().registerContentObserver(
            BUTTON_LIGHT_ENABLE_URI, true,
            new ContentObserver(new Handler()) {
                public void onChange(boolean selfChange) {
                    mButtonLightEnable = 1 == Settings.System.getInt(
                            mContext.getContentResolver(), BUTTON_LIGHT_ENABLE, 0);
                    if(!mButtonLightEnable) {
                        turnOffButtonLight();
                    }
                }
            });
        mButtonLightEnable = 1 == Settings.System.getInt(
                mContext.getContentResolver(), BUTTON_LIGHT_ENABLE, 1);

        mContext.getContentResolver().registerContentObserver(
            BUTTON_LIGHT_BRIGHTNESS_URI, true,
            new ContentObserver(new Handler()) {
                public void onChange(boolean selfChange) {
                    mButtonBrightness = Settings.System.getInt(mContext.getContentResolver(),
                            BUTTON_LIGHT_BRIGHTNESS, DEF_BUTTON_LIGHT_BRIGHTNESS);
                }
            });
        mButtonBrightness = Settings.System.getInt(mContext.getContentResolver(),
                BUTTON_LIGHT_BRIGHTNESS, DEF_BUTTON_LIGHT_BRIGHTNESS);

        ServiceManager.addService("hardware", mLegacyFlashlightHack);

        for (int i = 0; i < LIGHT_ID_COUNT; i++) {
            mLights[i] = new Light(i);
        }
    }

    protected void finalize() throws Throwable {
        finalize_native(mNativePointer);
        super.finalize();
    }

    public Light getLight(int id) {
        return mLights[id];
    }

    private Handler mH = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Light light = (Light)msg.obj;
            light.stopFlashing();
        }
    };

    private static native int init_native();
    private static native void finalize_native(int ptr);

    private static native void setLight_native(int ptr, int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);

    private final Context mContext;

    private int mNativePointer;
}
