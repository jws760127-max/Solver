package com.snapsolver.app;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.Locale;

/** 풀이 기록: 최근 풀이를 노트 모양으로 보여 줘요. */
public class ResultActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("풀이 기록");
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                ttsReady = true;
            }
        });
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                render();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/result.html");
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        render();
    }

    private void render() {
        String json = Prefs.history(this).toString();
        web.evaluateJavascript("window.render && render(" + JSONObject.quote(json) + ")", null);
    }

    private class Bridge {
        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (!ttsReady || text == null) return;
                if (!Voice.allowed(ResultActivity.this)) {
                    android.widget.Toast.makeText(ResultActivity.this,
                            "off".equals(Prefs.voiceMode(ResultActivity.this)) ? "소리로 읽기가 꺼져 있어요" : "이어폰을 끼면 들려 드려요",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                tts.setSpeechRate(Prefs.rate(ResultActivity.this));
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "again");
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface
        public void clearAll() {
            runOnUiThread(() -> {
                Prefs.clearHistory(ResultActivity.this);
                render();
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
