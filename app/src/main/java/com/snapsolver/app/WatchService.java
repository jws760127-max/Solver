package com.snapsolver.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * '자동 풀이'가 켜져 있는 동안 새로 찍힌 사진을 지켜보다가,
 * 문제가 보이면 Gemini로 풀어서 알림으로 보여주고 소리로 읽어 줘요.
 */
public class WatchService extends Service {
    static final String ACTION_STOP = "com.snapsolver.app.STOP";
    static final String ACTION_SOLVE_URI = "com.snapsolver.app.SOLVE_URI";
    static final String ACTION_SOLVE_LATEST = "com.snapsolver.app.SOLVE_LATEST";
    static final String EXTRA_URI = "uri";
    static final String CH_WATCH = "watch";
    static final String CH_RESULT = "result";
    static final int NID_WATCH = 1;
    static final int NID_RESULT = 2;

    private Handler main;
    private ExecutorService worker;
    private ContentObserver observer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
    private final Runnable check = () -> worker.execute(this::checkNewPhotos);

    static void start(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel w = new NotificationChannel(CH_WATCH, "자동 풀이 켜짐", NotificationManager.IMPORTANCE_MIN);
        w.setDescription("자동 풀이가 켜져 있는 동안 보이는 알림");
        w.setShowBadge(false);
        nm.createNotificationChannel(w);
        NotificationChannel r = new NotificationChannel(CH_RESULT, "풀이 결과", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("찍은 문제의 답");
        nm.createNotificationChannel(r);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels(this);
        main = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                ttsReady = true;
                if (pendingSpeech != null) {
                    String s = pendingSpeech;
                    pendingSpeech = null;
                    speak(s);
                }
            }
        });
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                main.removeCallbacks(check);
                main.postDelayed(check, 1500);
            }
        };
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        if (Prefs.lastSeen(this) == 0L) worker.execute(() -> Prefs.setLastSeen(this, newestId()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (ACTION_SOLVE_URI.equals(action) && intent.getStringExtra(EXTRA_URI) != null) {
            Uri u = Uri.parse(intent.getStringExtra(EXTRA_URI));
            worker.execute(() -> solve(u, true));
        } else if (ACTION_SOLVE_LATEST.equals(action)) {
            worker.execute(() -> {
                Uri u = newestCameraPhoto();
                if (u == null) notifyText("찍은 사진이 없어요", "카메라로 문제를 한 장 찍은 뒤 다시 눌러 주세요.", false);
                else solve(u, true);
            });
        }
        if (!Prefs.enabled(this) && !ACTION_SOLVE_URI.equals(action) && !ACTION_SOLVE_LATEST.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void goForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, WatchService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean on = Prefs.enabled(this);
        Notification n = new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(on ? "자동 풀이 켜짐" : "찍으면 풀이")
                .setContentText(on ? "카메라로 문제를 찍으면 바로 풀어 드려요" : "사진을 풀고 있어요")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "끄기", stopPi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NID_WATCH, n);
        }
    }

    // ───── 새 사진 찾기 ─────

    private String cameraSelection() {
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaStore.Images.Media.IS_PENDING + "=0 AND "
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE 'DCIM/%' AND "
                    + MediaStore.Images.Media.RELATIVE_PATH + " NOT LIKE 'DCIM/Screenshots%'";
        }
        return MediaStore.Images.Media.DATA + " LIKE '%/DCIM/%' AND " + MediaStore.Images.Media.DATA + " NOT LIKE '%/Screenshots/%'";
    }

    private long newestId() {
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private Uri newestCameraPhoto() {
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, cameraSelection(), null, MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst())
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
        } catch (Exception ignored) {
        }
        return null;
    }

    private void checkNewPhotos() {
        if (!Prefs.enabled(this)) return;
        long last = Prefs.lastSeen(this);
        long nowSec = System.currentTimeMillis() / 1000L;
        String sel = cameraSelection() + " AND " + MediaStore.Images.Media._ID + ">" + last
                + " AND " + MediaStore.Images.Media.DATE_ADDED + ">=" + (nowSec - 180);
        long newest = 0L;
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, sel, null, MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) newest = c.getLong(0);
        } catch (Exception e) {
            return;
        }
        if (newest <= last) return;
        Prefs.setLastSeen(this, newest);
        solve(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newest), false);
    }

    // ───── 풀기 ─────

    private void solve(Uri uri, boolean userAsked) {
        try {
            solveInner(uri, userAsked);
        } finally {
            // 자동 풀이가 꺼진 상태에서 한 장만 푼 경우, 읽기가 끝날 시간을 두고 서비스를 닫아요
            main.postDelayed(() -> {
                if (!Prefs.enabled(this)) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }, 60000);
        }
    }

    private void solveInner(Uri uri, boolean userAsked) {
        String key = Prefs.key(this);
        if (key.isEmpty()) {
            notifyText("Gemini 키가 필요해요", "앱을 열어 무료 키를 한 번만 넣어 주세요.", false);
            return;
        }
        notifyProgress();
        try {
            byte[] jpeg = Solver.prepare(this, uri);
            Solver.Result r = Solver.solve(key, Prefs.model(this), Prefs.level(this), jpeg);
            if (r.noProblem()) {
                cancelResult();
                if (userAsked) {
                    notifyText("문제를 찾지 못했어요", "문제가 잘 보이게 가까이, 밝은 곳에서 다시 찍어 주세요.", false);
                    speakIfOn("사진에서 문제를 찾지 못했어요. 더 가까이, 밝은 곳에서 다시 찍어 주세요.");
                }
                return;
            }
            JSONObject item = new JSONObject();
            item.put("id", System.currentTimeMillis());
            item.put("text", r.text);
            item.put("model", r.model);
            item.put("thumb", Solver.thumb(jpeg));
            Prefs.addHistory(this, item);
            String answer = Solver.plain(Solver.section(r.text, "답"));
            String voice = Solver.spoken(r.text);
            notifyText("답: " + answer, voice, true);
            speakIfOn(voice);
        } catch (Solver.GemError e) {
            notifyText("풀지 못했어요", e.getMessage(), false);
            if (userAsked) speakIfOn(e.getMessage());
        } catch (Exception e) {
            notifyText("풀지 못했어요", "사진을 읽는 중 문제가 생겼어요. 다시 찍어 주세요.", false);
        }
    }

    private void notifyProgress() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(this, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("문제를 푸는 중…")
                .setContentText("잠시만 기다려 주세요")
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        nm.notify(NID_RESULT, n);
    }

    private void cancelResult() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NID_RESULT);
    }

    private void notifyText(String title, String text, boolean openResults) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, openResults ? ResultActivity.class : MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, openResults ? 2 : 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + (openResults ? "\n\n눌러서 전체 풀이 보기" : "")))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        nm.cancel(NID_RESULT);
        nm.notify(NID_RESULT, n);
    }

    // ───── 소리 ─────

    private void speakIfOn(String text) {
        if (Voice.allowed(this) && text != null && !text.isEmpty()) main.post(() -> speak(text));
    }

    private void speak(String text) {
        if (!ttsReady) {
            pendingSpeech = text;
            return;
        }
        tts.setSpeechRate(Prefs.rate(this));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    @Override
    public void onDestroy() {
        try { getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) {}
        main.removeCallbacks(check);
        worker.shutdown();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
