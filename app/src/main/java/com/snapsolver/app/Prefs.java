package com.snapsolver.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 설정과 풀이 기록을 이 휴대폰 안에만 저장해요. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String key(Context c) { return sp(c).getString("key", ""); }
    static void setKey(Context c, String v) { sp(c).edit().putString("key", v).apply(); }

    static String model(Context c) { return sp(c).getString("model", "fast"); }
    static void setModel(Context c, String v) { sp(c).edit().putString("model", v).apply(); }

    static String level(Context c) { return sp(c).getString("level", "보통"); }
    static void setLevel(Context c, String v) { sp(c).edit().putString("level", v).apply(); }

    /** "earphones"(이어폰 꼈을 때만, 기본) · "always"(항상) · "off"(끄기) */
    static String voiceMode(Context c) {
        SharedPreferences s = sp(c);
        if (s.contains("voiceMode")) return s.getString("voiceMode", "earphones");
        return s.getBoolean("voice", true) ? "earphones" : "off";
    }
    static void setVoiceMode(Context c, String v) { sp(c).edit().putString("voiceMode", v).apply(); }

    static int interval(Context c) { return sp(c).getInt("interval", 10); }
    static void setInterval(Context c, int v) { sp(c).edit().putInt("interval", v).apply(); }

    static float rate(Context c) { return sp(c).getFloat("rate", 1.0f); }
    static void setRate(Context c, float v) { sp(c).edit().putFloat("rate", v).apply(); }

    static boolean enabled(Context c) { return sp(c).getBoolean("enabled", false); }
    static void setEnabled(Context c, boolean v) { sp(c).edit().putBoolean("enabled", v).apply(); }

    static long lastSeen(Context c) { return sp(c).getLong("lastSeen", 0L); }
    static void setLastSeen(Context c, long v) { sp(c).edit().putLong("lastSeen", v).apply(); }

    // ───── 풀이 기록 (최근 30개) ─────
    private static File histFile(Context c) { return new File(c.getFilesDir(), "history.json"); }

    static synchronized JSONArray history(Context c) {
        File f = histFile(c);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void addHistory(Context c, JSONObject item) {
        try {
            JSONArray old = history(c);
            JSONArray now = new JSONArray();
            now.put(item);
            for (int i = 0; i < old.length() && now.length() < 30; i++) now.put(old.get(i));
            try (FileOutputStream out = new FileOutputStream(histFile(c))) {
                out.write(now.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized void clearHistory(Context c) {
        //noinspection ResultOfMethodCallIgnored
        histFile(c).delete();
    }
}
