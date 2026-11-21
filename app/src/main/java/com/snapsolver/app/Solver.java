package com.snapsolver.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 사진을 준비해서 Gemini(무료 등급)에 보내고 답을 받아요. */
final class Solver {
    private Solver() {}

    // 2026년 11월 공식 문서 기준 무료 등급 모델. 앞에서부터 시도하고, 없거나 한도가 차면 다음으로 넘어가요.
    static final String[] FAST = {"gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest"};
    static final String[] SMART = {"gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"};

    static final String SYSTEM = "당신은 사진으로 받은 문제를 바로 풀어 주는 정확하고 친절한 선생님입니다. "
            + "수학, 과학, 영어, 국어, 코딩, 퍼즐, 생활 속 문제 등 어떤 종류든 다룹니다.\n"
            + "- 먼저 속으로 꼼꼼히 풀고 계산을 다시 검산한 뒤에 답을 쓰세요.\n"
            + "- 수식은 LaTeX로 쓰고 문장 속 수식은 \\( \\), 따로 보여줄 수식은 \\[ \\]로 감싸세요. 달러 기호는 쓰지 마세요.\n"
            + "- 한국어로 답하세요.\n"
            + "- 아래 [답변 형식]을 그대로 지키고, 앞뒤에 다른 말을 붙이지 마세요.";

    static final String FORMAT = "[답변 형식]\n"
            + "사진에 풀 문제(시험 문제, 숙제, 퀴즈, 질문, 계산 등)가 없으면 다른 말 없이 NO_PROBLEM 한 단어만 쓰세요.\n"
            + "문제가 있으면 아래 형식으로 쓰세요.\n\n"
            + "## 문제\n(사진 속 문제를 그대로 옮겨 적기. 여러 문제면 표시되어 있거나 가장 크게 보이는 하나, 판단이 어려우면 첫 번째)\n\n"
            + "## 답\n(최종 답만 짧게)\n\n"
            + "## 음성 안내\n(소리로 읽어 줄 2~3문장. 먼저 답을 말하고 핵심 이유를 짧게. 수식 기호, LaTeX, 괄호, 영어 약어 없이 말로 풀어 쓰기. 예: \"엑스 제곱 빼기 오 엑스 더하기 육\")\n\n"
            + "## 풀이\n### (첫 단계 제목)\n(설명)\n### (다음 단계 제목)\n(설명)\n(단계는 2~5개. 제목 앞에 번호를 붙이지 마세요)\n\n"
            + "## 핵심 포인트\n(기억할 점 한두 문장)";

    static String levelText(String level) {
        if ("쉽게".equals(level)) return "배경지식이 없는 초보자도 이해하도록 쉬운 말과 비유로";
        if ("전문가".equals(level)) return "전문 용어를 써서 깊이 있고 간결하게";
        return "일반 성인이 이해할 수 있는 수준으로";
    }

    static final class Result {
        final String text;
        final String model;
        Result(String text, String model) { this.text = text; this.model = model; }
        boolean noProblem() {
            String t = text == null ? "" : text.trim();
            return t.isEmpty() || t.startsWith("NO_PROBLEM") || section(t, "답").isEmpty();
        }
    }

    static final class GemError extends Exception {
        final int code;
        final boolean keyProblem;
        GemError(int code, String raw) {
            super(friendly(code, raw == null ? "" : raw));
            this.code = code;
            String r = raw == null ? "" : raw;
            this.keyProblem = r.contains("API key not valid") || r.contains("API_KEY_INVALID") || code == 403;
        }
        private static String friendly(int code, String m) {
            if (code == -1) return "인터넷에 연결되지 않았어요. 와이파이나 데이터를 확인해 주세요.";
            if (m.contains("API key not valid") || m.contains("API_KEY_INVALID")) return "Gemini 키가 올바르지 않아요. 앱을 열어 키를 다시 넣어 주세요.";
            if (code == 403) return "이 키로는 Gemini를 쓸 수 없어요. AI Studio에서 새 키를 만들어 넣어 주세요.";
            if (code == 429) return "무료 사용량이 잠시 다 찼어요. 1분 뒤 다시 찍어 보세요. 계속 안 되면 한국 시간 오후 5시쯤 다시 채워져요.";
            if (m.toLowerCase().contains("location")) return "지금 위치에서는 Gemini API를 쓸 수 없어요.";
            if (code >= 500) return "Gemini 서버가 바빠요. 잠시 후 다시 찍어 주세요.";
            return "풀지 못했어요" + (m.isEmpty() ? "." : ": " + m);
        }
    }

    /** 사진을 열어 방향을 바로잡고, 긴 변 2048px JPEG로 줄여요. */
    static byte[] prepare(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) throw new IOException("사진을 열 수 없어요");
        int sample = 1;
        while (longest / (sample * 2) >= 2048) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = cr.openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bm == null) throw new IOException("사진을 열 수 없어요");

        int rotate = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
            }
        } catch (Exception ignored) {
        }

        float scale = Math.min(1f, 2048f / Math.max(bm.getWidth(), bm.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rotate != 0) m.postRotate(rotate);
        Bitmap out = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        if (out != bm) out.recycle();
        bm.recycle();
        return bo.toByteArray();
    }

    /** 기록에 보여 줄 작은 사진 (data URL). */
    static String thumb(byte[] jpeg) {
        try {
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (b == null) return "";
            float s = 240f / Math.max(b.getWidth(), b.getHeight());
            Bitmap t = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            t.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            if (t != b) t.recycle();
            b.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static Result solve(String key, String modelPref, String level, byte[] jpeg) throws Exception {
        String[] chain = "smart".equals(modelPref) ? SMART : FAST;
        Exception last = null;
        boolean think = true;
        String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        for (int i = 0; i < chain.length; i++) {
            String model = chain[i];
            JSONObject body = new JSONObject();
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))));
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
                    .put(new JSONObject().put("text", "[설명 수준] " + levelText(level) + " 설명하세요.\n\n" + FORMAT));
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
            JSONObject gen = new JSONObject().put("maxOutputTokens", 8192);
            if (think) gen.put("thinkingConfig", new JSONObject().put("thinkingLevel", "MEDIUM"));
            body.put("generationConfig", gen);

            int code;
            String resp;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(
                        "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(120000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                con.setRequestProperty("x-goog-api-key", key);
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                code = con.getResponseCode();
                InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
                resp = readAll(is);
                con.disconnect();
            } catch (IOException e) {
                throw new GemError(-1, e.getMessage());
            }

            if (code >= 400) {
                String msg = errorMessage(resp);
                String low = msg.toLowerCase();
                if (code == 400 && think && low.contains("think")) { think = false; i--; continue; }
                if (code == 404 || code == 429 || code >= 500 || (code == 400 && low.contains("model") && !low.contains("key"))) {
                    last = new GemError(code, msg);
                    continue;
                }
                throw new GemError(code, msg);
            }

            JSONObject j = new JSONObject(resp);
            JSONArray cands = j.optJSONArray("candidates");
            StringBuilder sb = new StringBuilder();
            if (cands != null && cands.length() > 0) {
                JSONObject content = cands.getJSONObject(0).optJSONObject("content");
                JSONArray ps = content == null ? null : content.optJSONArray("parts");
                if (ps != null) {
                    for (int k = 0; k < ps.length(); k++) {
                        JSONObject p = ps.getJSONObject(k);
                        if (p.optBoolean("thought", false)) continue;
                        sb.append(p.optString("text", ""));
                    }
                }
            }
            return new Result(fromFirstHeader(sb.toString()), model);
        }
        throw last != null ? last : new GemError(0, "");
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private static String errorMessage(String resp) {
        try {
            return new JSONObject(resp).getJSONObject("error").optString("message", resp);
        } catch (Exception e) {
            return resp == null ? "" : resp;
        }
    }

    private static String fromFirstHeader(String t) {
        int i = t.indexOf("## ");
        if (i > 0 && (t.charAt(i - 1) == '\n')) return t.substring(i);
        return t;
    }

    /** "## 제목" 아래 내용을 꺼내요. */
    static String section(String text, String word) {
        String[] lines = text.split("\n");
        StringBuilder sb = null;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (sb != null) break;
                if (line.contains(word)) sb = new StringBuilder();
                continue;
            }
            if (sb != null) sb.append(line).append('\n');
        }
        return sb == null ? "" : sb.toString().trim();
    }

    /** 화면용 글자를 소리로 읽기 좋게 바꿔요. */
    static String plain(String s) {
        String t = s.replaceAll("\\\\\\(|\\\\\\)|\\\\\\[|\\\\\\]", " ")
                .replaceAll("\\\\[dt]?frac\\{([^}]*)\\}\\{([^}]*)\\}", "$2 분의 $1")
                .replaceAll("\\\\sqrt\\{([^}]*)\\}", "루트 $1")
                .replaceAll("\\^\\{?2\\}?", " 제곱")
                .replaceAll("\\\\times|\\\\cdot", " 곱하기 ")
                .replaceAll("\\\\[a-zA-Z]+", " ")
                .replaceAll("[{}*`#|]", " ")
                .replaceAll("\\s+", " ");
        return t.trim();
    }

    static String spoken(String text) {
        String v = section(text, "음성");
        if (!v.isEmpty()) return v;
        String a = section(text, "답");
        return a.isEmpty() ? "" : "답은 " + plain(a) + " 이에요.";
    }
}
