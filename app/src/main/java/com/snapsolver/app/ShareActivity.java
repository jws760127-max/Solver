package com.snapsolver.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 갤러리에서 '공유 → 찍으면 풀이로 풀기'로 보낸 사진을 받아요. */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Uri src = streamUri(getIntent());
            if (src == null) throw new IllegalStateException();
            if (Prefs.key(this).isEmpty()) {
                Toast.makeText(this, "먼저 앱을 열어 무료 Gemini 키를 넣어 주세요", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class));
            } else {
                // 받은 사진 권한은 이 화면에만 있어서, 앱 안에 복사해 두고 풀어요
                File f = new File(getCacheDir(), "shared.jpg");
                try (InputStream in = getContentResolver().openInputStream(src);
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Intent i = new Intent(this, WatchService.class)
                        .setAction(WatchService.ACTION_SOLVE_URI)
                        .putExtra(WatchService.EXTRA_URI, Uri.fromFile(f).toString());
                WatchService.start(this, i);
                Toast.makeText(this, "문제를 풀고 있어요. 알림을 확인하세요.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "사진을 받지 못했어요", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private static Uri streamUri(Intent i) {
        if (i == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        Object o = i.getParcelableExtra(Intent.EXTRA_STREAM);
        return o instanceof Uri ? (Uri) o : null;
    }
}
