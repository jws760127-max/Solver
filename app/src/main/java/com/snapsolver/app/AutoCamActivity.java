package com.snapsolver.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 자동 촬영: 카메라를 켜 두면 정해진 간격마다 화면을 확인하고,
 * 휴대폰이 멈춰 있고 페이지가 바뀌었을 때만 찍어서 풀어요.
 * 같은 페이지는 다시 보내지 않아서 무료 사용량을 아껴요.
 */
public class AutoCamActivity extends ComponentActivity {
    private static final int REQ_CAM = 20;
    private static final int G = 24;            // 화면 비교용 격자 크기
    private static final float CHANGED = 14f;   // 이보다 많이 달라지면 '새 페이지'
    private static final float STABLE = 8f;     // 이보다 적게 움직이면 '멈춰 있음'

    private PreviewView preview;
    private TextView status, answer;
    private ImageCapture capture;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private float[] curSig, oldSig, solvedSig;
    private long oldT = 0L;
    private boolean busy = false, running = false;
    private long nextAt = 0L;
    private int intervalMs = 10000;
    private String note = "";
    private MediaActionSound sound;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Runnable ticker = this::tick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        intervalMs = Prefs.interval(this) * 1000;
        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                ttsReady = true;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView header = new TextView(this);
        header.setText("자동 촬영 · " + Prefs.interval(this) + "초마다 확인 · 같은 페이지는 다시 안 찍어요");
        header.setTextColor(Color.WHITE);
        header.setTextSize(14);
        header.setPadding(dp(16), dp(36), dp(16), dp(12));
        header.setBackgroundColor(0x99000000);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(24));
        panel.setBackgroundColor(0xDD000000);

        answer = new TextView(this);
        answer.setText("풀 문제를 화면에 맞춰 두세요");
        answer.setTextColor(Color.WHITE);
        answer.setTextSize(22);
        answer.setTypeface(Typeface.DEFAULT_BOLD);
        answer.setOnClickListener(v -> startActivity(new Intent(this, ResultActivity.class)));
        panel.addView(answer);

        status = new TextView(this);
        status.setTextColor(0xFFDDDDDD);
        status.setTextSize(15);
        status.setPadding(0, dp(6), 0, dp(12));
        panel.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button now = button("지금 찍기", Color.rgb(38, 67, 184));
        now.setOnClickListener(v -> shootNow());
        Button history = button("풀이 보기", Color.rgb(84, 94, 128));
        history.setOnClickListener(v -> startActivity(new Intent(this, ResultActivity.class)));
        Button close = button("끝내기", Color.rgb(84, 94, 128));
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(now, lp);
        row.addView(history, new LinearLayout.LayoutParams(lp));
        row.addView(close, new LinearLayout.LayoutParams(lp));
        panel.addView(row);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        if (Prefs.key(this).isEmpty()) {
            answer.setText("먼저 첫 화면에서 무료 Gemini 키를 넣어 주세요");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAM) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else answer.setText("카메라 권한이 필요해요. 설정에서 카메라를 허용해 주세요.");
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analyzer, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture, analysis);
                running = true;
                nextAt = System.currentTimeMillis() + 3000;
                main.removeCallbacks(ticker);
                main.post(ticker);
            } catch (Exception e) {
                answer.setText("카메라를 켤 수 없어요");
                status.setText(String.valueOf(e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 미리보기 화면을 작은 격자로 줄여 두었다가, 움직임과 페이지 변화를 비교해요. */
    private void analyze(@NonNull ImageProxy img) {
        try {
            ImageProxy.PlaneProxy plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rs = plane.getRowStride(), ps = plane.getPixelStride();
            int w = img.getWidth(), h = img.getHeight(), limit = buf.limit();
            float[] s = new float[G * G];
            float mean = 0f;
            for (int gy = 0; gy < G; gy++) {
                for (int gx = 0; gx < G; gx++) {
                    float sum = 0f;
                    int n = 0;
                    for (int dy = 0; dy < 3; dy++) {
                        for (int dx = 0; dx < 3; dx++) {
                            int x = (int) ((gx + (dx + 0.5f) / 3f) * w / G);
                            int y = (int) ((gy + (dy + 0.5f) / 3f) * h / G);
                            int idx = y * rs + x * ps;
                            if (idx >= 0 && idx < limit) {
                                sum += (buf.get(idx) & 0xFF);
                                n++;
                            }
                        }
                    }
                    float v = n > 0 ? sum / n : 0f;
                    s[gy * G + gx] = v;
                    mean += v;
                }
            }
            mean /= (G * G);
            for (int i = 0; i < s.length; i++) s[i] -= mean;
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (curSig == null) {
                    oldT = now;
                } else if (now - oldT >= 1000) {
                    oldSig = curSig;
                    oldT = now;
                }
                curSig = s;
            }
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    private static float diff(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 999f;
        float d = 0f;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length;
    }

    private void tick() {
        if (!running) return;
        main.postDelayed(ticker, 1000);
        if (busy) return;
        long left = nextAt - System.currentTimeMillis();
        if (left > 0) {
            long sec = (left + 999) / 1000;
            status.setText((note.isEmpty() ? "" : note + "\n") + sec + "초 뒤 확인해요 · 페이지가 바뀌면 자동으로 찍어요");
            return;
        }
        float[] cur, old;
        synchronized (lock) {
            cur = curSig;
            old = oldSig;
        }
        if (cur == null || old == null) return;
        if (diff(cur, old) > STABLE) {
            status.setText("움직이고 있어요. 휴대폰을 잠깐 멈춰 주세요");
            nextAt = System.currentTimeMillis() + 1000;
            return;
        }
        if (solvedSig != null && diff(cur, solvedSig) < CHANGED) {
            note = "같은 페이지예요. 다음 문제로 넘기면 찍어요";
            nextAt = System.currentTimeMillis() + intervalMs;
            return;
        }
        shoot(cur);
    }

    private void shootNow() {
        if (busy || capture == null) return;
        float[] cur;
        synchronized (lock) { cur = curSig; }
        shoot(cur);
    }

    private void shoot(float[] sig) {
        if (capture == null || busy) return;
        busy = true;
        status.setText("찍는 중…");
        try { sound.play(MediaActionSound.SHUTTER_CLICK); } catch (Exception ignored) {}
        capture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                final int rot = image.getImageInfo().getRotationDegrees();
                final byte[] bytes = toBytes(image);
                status.setText("문제를 푸는 중…");
                worker.execute(() -> solve(bytes, rot, sig));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                busy = false;
                note = "찍지 못했어요. 다시 해 볼게요";
                nextAt = System.currentTimeMillis() + 2000;
            }
        });
    }

    private static byte[] toBytes(ImageProxy image) {
        try {
            ByteBuffer b = image.getPlanes()[0].getBuffer();
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } finally {
            image.close();
        }
    }

    private void solve(byte[] raw, int rot, float[] sig) {
        try {
            byte[] jpeg = Solver.prepareBytes(raw, rot);
            Solver.Result r = Solver.solve(Prefs.key(this), Prefs.model(this), Prefs.level(this), jpeg);
            if (!r.noProblem()) {
                JSONObject item = new JSONObject();
                item.put("id", System.currentTimeMillis());
                item.put("text", r.text);
                item.put("model", r.model);
                item.put("thumb", Solver.thumb(jpeg));
                Prefs.addHistory(this, item);
            }
            main.post(() -> {
                solvedSig = sig;
                busy = false;
                nextAt = System.currentTimeMillis() + intervalMs;
                if (r.noProblem()) {
                    note = "문제가 안 보여요. 문제를 화면 가운데에 맞춰 주세요";
                    return;
                }
                answer.setText("답: " + Solver.plain(Solver.section(r.text, "답")));
                note = "풀었어요 · 답을 누르면 전체 풀이가 보여요";
                speak(Solver.spoken(r.text));
            });
        } catch (Solver.GemError e) {
            main.post(() -> {
                busy = false;
                note = e.getMessage();
                nextAt = System.currentTimeMillis() + (e.code == 429 ? 60000 : intervalMs);
            });
        } catch (Exception e) {
            main.post(() -> {
                busy = false;
                note = "사진을 읽지 못했어요. 다시 해 볼게요";
                nextAt = System.currentTimeMillis() + intervalMs;
            });
        }
    }

    private void speak(String text) {
        if (!ttsReady || text == null || text.isEmpty() || !Voice.allowed(this)) return;
        tts.setSpeechRate(Prefs.rate(this));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "auto");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (capture != null && !running) {
            running = true;
            nextAt = System.currentTimeMillis() + 2000;
            main.post(ticker);
        }
    }

    @Override
    protected void onPause() {
        running = false;
        main.removeCallbacks(ticker);
        if (tts != null) tts.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        running = false;
        main.removeCallbacks(ticker);
        worker.shutdown();
        analyzer.shutdown();
        if (tts != null) tts.shutdown();
        if (sound != null) sound.release();
        super.onDestroy();
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
