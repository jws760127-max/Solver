package com.snapsolver.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 설정 화면: 키 넣기, 권한, 자동 풀이 켜기/끄기. */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10;
    private static final int BLUE = Color.rgb(38, 67, 184);
    private static final int RED = Color.rgb(214, 44, 27);
    private static final int INK = Color.rgb(20, 28, 53);
    private static final int MUTED = Color.rgb(84, 94, 128);

    private TextView status, permStatus, batteryStatus, keyStatus;
    private Button toggle;
    private EditText keyIn;
    private boolean enableAfterPermission = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WatchService.ensureChannels(this);
        setTitle("찍으면 풀이");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(16), p, dp(40));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("찍으면 풀이", 28, INK, true);
        root.addView(title);
        root.addView(text("자동 풀이를 켜 두면, 휴대폰 카메라로 문제를 찍기만 해도 알림으로 답이 오고 소리로 읽어 줘요.", 16, MUTED, false));

        status = text("", 18, INK, true);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(16);
        root.addView(status, sp);

        toggle = bigButton("");
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, spaced(10));

        // 1. 키
        root.addView(header("1. 무료 Gemini 키 넣기 (처음 한 번)"));
        root.addView(text("① 아래 '키 받는 곳 열기'를 눌러 구글 계정으로 로그인해요.\n② 'API 키 만들기(Create API key)'를 누르고 키를 복사해요. 카드 등록은 필요 없어요.\n③ 아래 칸에 붙여넣고 '키 저장'을 눌러요.", 15, INK, false));
        Button openKey = button("키 받는 곳 열기 (aistudio.google.com)");
        openKey.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))));
        root.addView(openKey, spaced(8));
        keyIn = new EditText(this);
        keyIn.setHint("AIza로 시작하는 키");
        keyIn.setSingleLine(true);
        keyIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyIn.setTextSize(16);
        root.addView(keyIn, spaced(8));
        Button saveKey = button("키 저장");
        saveKey.setOnClickListener(v -> {
            String k = keyIn.getText().toString().trim().replace("\"", "");
            if (k.isEmpty()) { toast("키를 붙여넣어 주세요"); return; }
            Prefs.setKey(this, k);
            keyIn.setText("");
            toast("키를 저장했어요");
            refresh();
        });
        root.addView(saveKey, spaced(4));
        keyStatus = text("", 14, MUTED, false);
        root.addView(keyStatus);

        // 2. 권한
        root.addView(header("2. 권한 허용"));
        Button perms = button("사진·알림 권한 허용하기");
        perms.setOnClickListener(v -> askPermissions());
        root.addView(perms, spaced(4));
        permStatus = text("", 14, MUTED, false);
        root.addView(permStatus);
        Button battery = button("꺼지지 않게 배터리 제한 풀기");
        battery.setOnClickListener(v -> askBattery());
        root.addView(battery, spaced(10));
        batteryStatus = text("", 14, MUTED, false);
        root.addView(batteryStatus);

        // 3. 설정
        root.addView(header("3. 설정"));
        root.addView(text("AI", 15, MUTED, true));
        RadioGroup model = radios(new String[]{"빠르게 (무료로 하루 수백 번)", "똑똑하게 (무료로 하루 수십 번)"},
                "smart".equals(Prefs.model(this)) ? 1 : 0,
                i -> Prefs.setModel(this, i == 1 ? "smart" : "fast"));
        root.addView(model);
        root.addView(text("설명 수준", 15, MUTED, true));
        String[] levels = {"쉽게", "보통", "전문가"};
        int li = 1;
        for (int i = 0; i < levels.length; i++) if (levels[i].equals(Prefs.level(this))) li = i;
        RadioGroup level = radios(levels, li, i -> Prefs.setLevel(this, levels[i]));
        level.setOrientation(RadioGroup.HORIZONTAL);
        root.addView(level);
        Switch voice = new Switch(this);
        voice.setText("답을 소리로 읽기");
        voice.setTextSize(16);
        voice.setChecked(Prefs.voice(this));
        voice.setOnCheckedChangeListener((bv, on) -> Prefs.setVoice(this, on));
        root.addView(voice, spaced(8));
        TextView rateLabel = text("말 빠르기 ×" + String.format("%.1f", Prefs.rate(this)), 15, MUTED, false);
        root.addView(rateLabel, spaced(8));
        SeekBar rate = new SeekBar(this);
        rate.setMax(8);
        rate.setProgress(Math.round((Prefs.rate(this) - 0.7f) * 10));
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                float r = 0.7f + v / 10f;
                Prefs.setRate(MainActivity.this, r);
                rateLabel.setText("말 빠르기 ×" + String.format("%.1f", r));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(rate);

        // 4. 시험하기·기록
        root.addView(header("4. 써 보기"));
        Button test = button("방금 찍은 사진으로 풀어 보기");
        test.setOnClickListener(v -> {
            if (Prefs.key(this).isEmpty()) { toast("먼저 키를 넣어 주세요"); return; }
            if (!hasPhotoPermission()) { askPermissions(); return; }
            WatchService.start(this, new Intent(this, WatchService.class).setAction(WatchService.ACTION_SOLVE_LATEST));
            toast("가장 최근 사진을 풀고 있어요. 알림을 확인하세요.");
        });
        root.addView(test, spaced(4));
        Button history = button("풀이 기록 보기");
        history.setOnClickListener(v -> startActivity(new Intent(this, ResultActivity.class)));
        root.addView(history, spaced(8));
        root.addView(text("갤러리에서 사진을 '공유 → 찍으면 풀이로 풀기'로 보내도 바로 풀어요.", 14, MUTED, false), spaced(8));

        TextView privacy = text("알아 두기: 자동 풀이가 켜져 있는 동안 카메라로 찍는 사진은 Gemini로 보내져요. 문제가 아닌 사진은 조용히 넘어가요. Gemini 무료 등급은 보낸 내용이 Google 서비스 개선에 쓰일 수 있어요. 필요 없을 때는 알림의 '끄기'로 꺼 두세요.", 13, MUTED, false);
        root.addView(privacy, spaced(20));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = Prefs.enabled(this);
        status.setText(on ? "● 자동 풀이 켜짐\n카메라로 문제를 찍으면 바로 풀어 드려요" : "○ 자동 풀이 꺼짐");
        status.setTextColor(on ? Color.WHITE : INK);
        status.setBackground(round(on ? BLUE : Color.rgb(238, 242, 252), 16));
        toggle.setText(on ? "자동 풀이 끄기" : "자동 풀이 켜기");
        toggle.setBackground(round(on ? Color.rgb(84, 94, 128) : RED, 18));
        keyStatus.setText(Prefs.key(this).isEmpty() ? "아직 키가 없어요" : "✓ 키가 저장돼 있어요");
        keyStatus.setTextColor(Prefs.key(this).isEmpty() ? RED : Color.rgb(17, 128, 79));
        boolean photo = hasPhotoPermission();
        boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permStatus.setText((photo ? "✓ 사진 권한 허용됨" : "✗ 사진 권한이 필요해요 ('모두 허용'을 골라 주세요)") + "   "
                + (notif ? "✓ 알림 허용됨" : "✗ 알림 권한이 필요해요"));
        permStatus.setTextColor(photo && notif ? Color.rgb(17, 128, 79) : RED);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean free = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.setText(free ? "✓ 배터리 제한 없음" : "권장: 배터리 제한을 풀면 화면이 꺼져 있어도 잘 동작해요. (설정 → 애플리케이션 → 찍으면 풀이 → 배터리 → 제한 없음)");
        batteryStatus.setTextColor(free ? Color.rgb(17, 128, 79) : MUTED);
    }

    private void onToggle() {
        if (Prefs.enabled(this)) {
            Prefs.setEnabled(this, false);
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACTION_STOP));
            refresh();
            return;
        }
        if (Prefs.key(this).isEmpty()) {
            toast("먼저 1번에서 무료 Gemini 키를 넣어 주세요");
            keyIn.requestFocus();
            return;
        }
        if (!hasPhotoPermission()) {
            enableAfterPermission = true;
            askPermissions();
            return;
        }
        enable();
    }

    private void enable() {
        Prefs.setEnabled(this, true);
        WatchService.start(this, new Intent(this, WatchService.class));
        refresh();
        toast("자동 풀이를 켰어요. 이제 카메라로 문제를 찍어 보세요!");
    }

    private boolean hasPhotoPermission() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(list.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
        if (code != REQ_PERMS) return;
        if (hasPhotoPermission()) {
            if (enableAfterPermission) enable();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("사진 권한이 필요해요")
                    .setMessage("새로 찍은 사진을 보려면 사진 권한을 '모두 허용'으로 해 주셔야 해요. '일부만 허용'을 고르면 새 사진을 볼 수 없어요.\n\n설정 → 권한 → 사진 및 동영상 → 모두 허용")
                    .setPositiveButton("설정 열기", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("닫기", null)
                    .show();
        }
        enableAfterPermission = false;
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    // ───── 화면 도우미 ─────
    private interface OnPick { void pick(int i); }

    private RadioGroup radios(String[] labels, int checked, OnPick onPick) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(labels[i]);
            r.setTextSize(16);
            r.setPadding(dp(4), dp(8), dp(12), dp(8));
            g.addView(r);
            if (i == checked) g.check(r.getId());
        }
        g.setOnCheckedChangeListener((group, id) -> {
            for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i).getId() == id) onPick.pick(i);
        });
        return g;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 19, INK, true);
        t.setPadding(0, dp(26), 0, dp(6));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(BLUE);
        b.setBackground(stroke());
        b.setMinHeight(dp(52));
        return b;
    }

    private Button bigButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(64));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable stroke() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setStroke(dp(2), BLUE);
        d.setCornerRadius(dp(14));
        return d;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
