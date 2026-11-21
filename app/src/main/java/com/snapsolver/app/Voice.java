package com.snapsolver.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/** 소리로 읽어도 되는지 정해요. 기본은 '이어폰을 꼈을 때만'이라 도서관에서도 조용해요. */
final class Voice {
    private Voice() {}

    static boolean headset(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET
                    || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                return true;
            }
        }
        return false;
    }

    static boolean allowed(Context c) {
        String m = Prefs.voiceMode(c);
        if ("always".equals(m)) return true;
        return "earphones".equals(m) && headset(c);
    }
}
