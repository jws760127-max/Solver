package com.snapsolver.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 휴대폰을 껐다 켜거나 앱을 업데이트해도 자동 풀이가 다시 켜지게 해요. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Prefs.enabled(c)) return;
        try {
            WatchService.start(c, new Intent(c, WatchService.class));
        } catch (Exception ignored) {
            // 일부 기기는 부팅 직후 시작을 막아요. 앱을 한 번 열면 다시 켜져요.
        }
    }
}
