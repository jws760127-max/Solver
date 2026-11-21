// SolveCam — 와이파이 DIY 카메라용 '찍으면 풀이'
//
// 처음 켜면 "SolveCam-Setup" 와이파이가 생겨요. 휴대폰으로 연결하면 설정 화면이 뜨고,
// 집 와이파이와 무료 Gemini 키를 넣으면 끝이에요.
// 그다음부터는 같은 와이파이의 휴대폰·PC 브라우저에서 http://solvecam.local (또는 카메라 IP)을 열면 돼요.
//
// 카메라는 사진(/capture)과 풀이 화면(/)을 주고, 실제 풀이는 화면을 연 기기가 Gemini로 해요.
// 여러 기기가 열려 있으면 한 기기만 풀이를 맡고, 결과는 카메라를 거쳐 다른 기기에도 보여요.

#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include "camera_pins.h"
#include "page.h"

WebServer server(80);
DNSServer dns;
Preferences prefs;

bool setupMode = false;
bool cameraOk = false;
String wifiSsid, wifiPass, geminiKey, aiModel = "fast";
int intervalSec = 10;
int rotation = 0;
String lastResult = "{}";
uint32_t resultId = 0;
String solverId = "";
uint32_t solverSeen = 0;
uint32_t restartAt = 0;
bool flashOn = false;

// ───────── 카메라 ─────────
bool initCamera() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM;
  c.pin_d1 = Y3_GPIO_NUM;
  c.pin_d2 = Y4_GPIO_NUM;
  c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM;
  c.pin_d5 = Y7_GPIO_NUM;
  c.pin_d6 = Y8_GPIO_NUM;
  c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM;
  c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM;
  c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM;
  c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM;
  c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (psramFound()) {
    c.frame_size = FRAMESIZE_UXGA;  // 1600x1200: 작은 글씨도 읽히게
    c.jpeg_quality = 12;
    c.fb_count = 2;
    c.fb_location = CAMERA_FB_IN_PSRAM;
  } else {
    c.frame_size = FRAMESIZE_SVGA;
    c.jpeg_quality = 12;
    c.fb_count = 1;
    c.fb_location = CAMERA_FB_IN_DRAM;
  }
  if (esp_camera_init(&c) != ESP_OK) return false;
  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    s->set_brightness(s, 1);
    s->set_saturation(s, -1);
  }
  return true;
}

// ───────── 도우미 ─────────
void cors() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Cache-Control", "no-store");
}

String jsonEscape(const String &s) {
  String o;
  o.reserve(s.length() + 8);
  for (size_t i = 0; i < s.length(); i++) {
    char ch = s[i];
    if (ch == '"' || ch == '\\') { o += '\\'; o += ch; }
    else if (ch == '\n') o += "\\n";
    else if (ch == '\r') o += "\\r";
    else if ((uint8_t)ch < 0x20) o += ' ';
    else o += ch;
  }
  return o;
}

void sendBytes(const char *type, const uint8_t *buf, size_t len, bool gzip) {
  if (gzip) server.sendHeader("Content-Encoding", "gzip");
  server.setContentLength(len);
  server.send(200, type, "");
  WiFiClient cl = server.client();
  size_t sent = 0;
  while (sent < len) {
    size_t n = cl.write(buf + sent, len - sent);
    if (n == 0) break;
    sent += n;
  }
}

// ───────── 풀이 화면 (같은 와이파이) ─────────
void handlePage() {
  sendBytes("text/html; charset=utf-8", index_html_gz, index_html_gz_len, true);
}

void handleCapture() {
  cors();
  if (!cameraOk) { server.send(500, "text/plain", "camera init failed"); return; }
  camera_fb_t *fb = esp_camera_fb_get();  // 오래된 프레임은 버리고
  if (fb) esp_camera_fb_return(fb);
  fb = esp_camera_fb_get();                // 방금 찍힌 프레임을 보내요
  if (!fb) { server.send(503, "text/plain", "camera busy"); return; }
  sendBytes("image/jpeg", fb->buf, fb->len, false);
  esp_camera_fb_return(fb);
}

void handleConfigGet() {
  cors();
  String j = "{\"key\":\"" + jsonEscape(geminiKey) + "\",\"interval\":" + String(intervalSec) +
             ",\"model\":\"" + jsonEscape(aiModel) + "\",\"flash\":" + String(FLASH_GPIO >= 0 ? "true" : "false") +
             ",\"rotation\":" + String(rotation) + ",\"camera\":" + String(cameraOk ? "true" : "false") + ",\"board\":\"" BOARD_NAME "\"}";
  server.send(200, "application/json", j);
}

void handleConfigPost() {
  if (server.hasArg("key")) { geminiKey = server.arg("key"); geminiKey.trim(); prefs.putString("gkey", geminiKey); }
  if (server.hasArg("interval")) { intervalSec = constrain(server.arg("interval").toInt(), 3, 120); prefs.putInt("interval", intervalSec); }
  if (server.hasArg("rotation")) { int r = server.arg("rotation").toInt(); rotation = (r == 90 || r == 180 || r == 270) ? r : 0; prefs.putInt("rot", rotation); }
  if (server.hasArg("model")) { aiModel = server.arg("model") == "smart" ? "smart" : "fast"; prefs.putString("model", aiModel); }
  cors();
  server.send(200, "application/json", "{\"ok\":true}");
}

// 여러 기기 중 한 기기만 자동 풀이를 맡게 해요 (15초 동안 소식이 없으면 다른 기기가 넘겨받아요)
void handleHello() {
  String id = server.arg("id");
  bool want = server.arg("want") == "1";
  uint32_t now = millis();
  if (id.length()) {
    if (want && (solverId == "" || solverId == id || now - solverSeen > 15000)) { solverId = id; solverSeen = now; }
    else if (!want && solverId == id) { solverId = ""; }
  }
  cors();
  server.send(200, "application/json", "{\"solver\":\"" + jsonEscape(solverId) + "\",\"rid\":" + String(resultId) + "}");
}

void handleResultGet() {
  cors();
  server.send(200, "application/json", lastResult);
}

void handleResultPost() {
  String body = server.arg("plain");
  if (body.length() > 0 && body.length() < 60000) { lastResult = body; resultId++; }
  cors();
  server.send(200, "application/json", "{\"rid\":" + String(resultId) + "}");
}

void handleFlash() {
  flashOn = server.arg("on") == "1";
  if (FLASH_GPIO >= 0) digitalWrite(FLASH_GPIO, flashOn ? HIGH : LOW);
  cors();
  server.send(200, "application/json", String("{\"on\":") + (flashOn ? "true" : "false") + "}");
}

void handleForget() {
  prefs.remove("ssid");
  prefs.remove("pass");
  cors();
  server.send(200, "application/json", "{\"ok\":true}");
  restartAt = millis() + 1500;
}

void handleOptions() {
  cors();
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
  server.send(204);
}

// ───────── 처음 설정 (SolveCam-Setup 와이파이) ─────────
const char SETUP_HEAD[] PROGMEM = R"HTML(<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SolveCam 설정</title>
<style>body{margin:0;font:17px/1.6 system-ui,sans-serif;background:#f5f7fc;color:#141c35}main{max-width:480px;margin:0 auto;padding:24px 18px}h1{font-size:26px;margin:0 0 6px}p{color:#545e80}label{display:block;font-weight:700;margin:18px 0 6px}input,select{width:100%;box-sizing:border-box;min-height:50px;font-size:17px;padding:0 12px;border:1.5px solid #d5dbea;border-radius:12px;background:#fff}button{width:100%;min-height:56px;margin-top:24px;border:0;border-radius:14px;background:#2643b8;color:#fff;font-size:18px;font-weight:700}small{color:#545e80}.ok{background:#eef8f2;border-radius:12px;padding:14px}.bad{background:#fdeeee;border-radius:12px;padding:14px}a{color:#2643b8}</style></head><body><main>)HTML";

String setupForm(const String &msg) {
  String h = FPSTR(SETUP_HEAD);
  h += "<h1>SolveCam 설정</h1><p>카메라를 집 와이파이에 연결하고, 무료 Gemini 키를 넣어 주세요. 한 번만 하면 돼요.</p>";
  if (msg.length()) h += "<p class=\"bad\">" + msg + "</p>";
  h += "<form method=\"post\" action=\"/save\"><label for=\"ssid\">집 와이파이</label><select id=\"ssid\" name=\"ssid\">";
  int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_FAILED || n == WIFI_SCAN_RUNNING) n = WiFi.scanNetworks();
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (!s.length()) continue;
    h += "<option value=\"" + s + "\">" + s + " (" + String(WiFi.RSSI(i)) + "dBm)</option>";
  }
  h += "</select><small>목록에 없으면 아래에 직접 적어 주세요. 2.4GHz 와이파이만 연결돼요.</small>";
  h += "<input name=\"ssid2\" placeholder=\"와이파이 이름 직접 입력 (선택)\" style=\"margin-top:8px\">";
  h += "<label for=\"pass\">와이파이 비밀번호</label><input id=\"pass\" name=\"pass\" type=\"password\">";
  h += "<label for=\"key\">무료 Gemini 키 (나중에 넣어도 돼요)</label><input id=\"key\" name=\"key\" placeholder=\"AIza...\" autocomplete=\"off\" value=\"" + geminiKey + "\">";
  h += "<small>키 받는 곳: aistudio.google.com/apikey → API 키 만들기 → 복사</small>";
  h += "<button>연결하기</button></form></main></body></html>";
  return h;
}

void handleSetupPage() { server.send(200, "text/html; charset=utf-8", setupForm("")); }

void handleSetupSave() {
  String ssid = server.arg("ssid2");
  ssid.trim();
  if (!ssid.length()) ssid = server.arg("ssid");
  String pass = server.arg("pass");
  String key = server.arg("key");
  key.trim();
  if (key.length()) { geminiKey = key; prefs.putString("gkey", key); }
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) delay(250);
  if (WiFi.status() != WL_CONNECTED) {
    server.send(200, "text/html; charset=utf-8", setupForm("\"" + ssid + "\"에 연결하지 못했어요. 비밀번호를 다시 확인해 주세요."));
    return;
  }
  prefs.putString("ssid", ssid);
  prefs.putString("pass", pass);
  String ip = WiFi.localIP().toString();
  String h = FPSTR(SETUP_HEAD);
  h += "<h1>연결됐어요!</h1><div class=\"ok\"><p>카메라가 다시 켜져요. 휴대폰을 원래 와이파이(" + ssid + ")로 돌린 뒤 아래 주소를 열어 주세요.</p>";
  h += "<p style=\"font-size:22px;font-weight:700;color:#141c35\">http://" + ip + "</p><p>또는 http://solvecam.local</p></div>";
  h += "<p>이 주소를 즐겨찾기나 홈 화면에 추가해 두면 편해요.</p></main></body></html>";
  server.send(200, "text/html; charset=utf-8", h);
  restartAt = millis() + 4000;
}

void startSetupMode() {
  setupMode = true;
  WiFi.disconnect(true);
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP("SolveCam-Setup");
  delay(200);
  WiFi.scanNetworks(true);  // 목록은 미리 찾아 둬요
  dns.start(53, "*", WiFi.softAPIP());
  server.on("/", HTTP_GET, handleSetupPage);
  server.on("/save", HTTP_POST, handleSetupSave);
  server.onNotFound([]() {
    server.sendHeader("Location", "http://192.168.4.1/");
    server.send(302, "text/plain", "");
  });
  server.begin();
  Serial.println("[SolveCam] 설정 모드: 'SolveCam-Setup' 와이파이에 연결하세요 (http://192.168.4.1)");
}

// ───────── 시작 ─────────
void setup() {
  Serial.begin(115200);
  delay(300);
  if (FLASH_GPIO >= 0) { pinMode(FLASH_GPIO, OUTPUT); digitalWrite(FLASH_GPIO, LOW); }
  prefs.begin("solvecam", false);
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  geminiKey = prefs.getString("gkey", "");
  intervalSec = prefs.getInt("interval", 10);
  aiModel = prefs.getString("model", "fast");
  rotation = prefs.getInt("rot", 0);

  cameraOk = initCamera();
  Serial.println(cameraOk ? "[SolveCam] 카메라 준비됨" : "[SolveCam] 카메라를 찾지 못했어요 (선 연결 확인)");

  if (!wifiSsid.length()) { startSetupMode(); return; }

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setHostname("solvecam");
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) delay(250);
  if (WiFi.status() != WL_CONNECTED) { startSetupMode(); return; }

  if (MDNS.begin("solvecam")) MDNS.addService("http", "tcp", 80);

  server.on("/", HTTP_GET, handlePage);
  server.on("/capture", HTTP_GET, handleCapture);
  server.on("/config", HTTP_GET, handleConfigGet);
  server.on("/config", HTTP_POST, handleConfigPost);
  server.on("/hello", HTTP_POST, handleHello);
  server.on("/result", HTTP_GET, handleResultGet);
  server.on("/result", HTTP_POST, handleResultPost);
  server.on("/flash", HTTP_POST, handleFlash);
  server.on("/forget", HTTP_POST, handleForget);
  server.onNotFound([]() {
    if (server.method() == HTTP_OPTIONS) { handleOptions(); return; }
    server.send(404, "text/plain", "not found");
  });
  server.begin();
  Serial.print("[SolveCam] 준비 완료: http://");
  Serial.print(WiFi.localIP());
  Serial.println("  (또는 http://solvecam.local)");
}

void loop() {
  if (setupMode) dns.processNextRequest();
  server.handleClient();
  if (restartAt && millis() > restartAt) ESP.restart();
  static uint32_t lastCheck = 0;
  if (!setupMode && millis() - lastCheck > 10000) {
    lastCheck = millis();
    if (WiFi.status() != WL_CONNECTED) WiFi.reconnect();
  }
  delay(1);
}
