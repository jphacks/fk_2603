/*
 * 二度寝防止アラーム - M5Stack Basic + FSR406
 *
 * 必要ライブラリ (Arduino IDE ライブラリマネージャ):
 *   - M5Unified
 *   - ArduinoJson (v7)
 * ボード: M5Core (M5Stack ボードパッケージ)
 * Partition Scheme: Huge APP (3MB No OTA/1MB SPIFFS)  ※BLE が大きく初期値には入らない
 *
 * 配線: 3.3V -- FSR406 --+-- GPIO36
 *                        |
 *                      10kΩ
 *                        |
 *                       GND
 *
 * スマホとは BLE で通信する（Wi-Fi は使わない）。時刻はスマホから受け取る。
 * ボタン: A(左)=音量アップ / B(中)=音量ダウン / C(右)=起床を確定（ベッドの外でのみ）
 * シリアルモニタ: 115200bps でデバッグ出力
 */

#include <M5Unified.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <ArduinoJson.h>
#include <sys/time.h>
#include <time.h>

// ============================================================
// 設定値（ここを変更して調整する）
// ============================================================
// --- BLE（UUID は Android 側 M5Link.kt と合わせる）---
const char* BLE_NAME = "WakeUp";
const char* SERVICE_UUID = "6e0f0001-3b6a-4c1e-9b3a-2a4f8c1d7e01";
const char* STATUS_UUID = "6e0f0002-3b6a-4c1e-9b3a-2a4f8c1d7e01";   // read / notify: 状態 JSON
const char* COMMAND_UUID = "6e0f0003-3b6a-4c1e-9b3a-2a4f8c1d7e01";  // write: コマンド JSON
const uint32_t STATUS_NOTIFY_MS = 1000;  // 接続中に状態を送る間隔

// --- 圧力センサ ---
const int FSR_PIN = 36;               // ADC1
const int IN_BED_THRESHOLD = 300;     // raw(0-4095) がこれ以上なら「ベッドにいる」
const int IN_BED_HYSTERESIS = 50;     // 閾値付近でのばたつき防止（THRESHOLD - これ 未満で「いない」）
const int FSR_SAMPLES = 8;            // 平均を取るサンプル数

// --- 時間設定（秒）---
const uint32_t WAKE_CONFIRM_SEC = 10;            // 圧力なしがこの秒数続いたら「起床」（音はベッドを出た瞬間に止まる）
const uint32_t BED_WAIT_SEC = 10 * 60;           // アラーム時刻にベッドにいなければ、この時間内にベッドに入ったら鳴らす
const uint32_t SESSION_GAP_SEC = 20 * 60;        // ベッドを出てこの時間内に戻れば同じ睡眠が続いているとみなす
const uint32_t SLEEP_START_CONFIRM_SEC = 20 * 60; // 圧力ありがこの秒数続いたら睡眠開始（開始時刻はベッドに入った瞬間）
const uint32_t SENSOR_NOISE_SEC = 10;            // 在床中にこの秒数未満だけ圧力が消えても、センサーの誤差として連続扱い
const uint32_t RESLEEP_WINDOW_SEC = SESSION_GAP_SEC; // 起床後この時間内にベッドに戻ったら二度寝扱い
const uint32_t RESLEEP_DETECT_SEC = 60;          // 圧力ありがこの秒数続いたら「二度寝」（寝返りなどの誤検出防止）
const uint32_t RESLEEP_ALARM_DELAY_SEC = 5 * 60; // ベッドに戻ってからアラームを鳴らすまで
// アラームはベッドにいる間だけ鳴る（ベッドを出ると止まり、戻るとまた鳴る。C ボタンはベッドの外でのみ有効）
// スマホはアラームに関係なく、ベッドにいる間は常にロックされる

// --- 音量 ---
const int VOLUME_DEFAULT = 1;  // 起動時の音量（最小=1、ミュートではない）
const int VOLUME_MIN = 1;
const int VOLUME_MAX = 10;
// 音量レベル -> スピーカー音量(0-255)
const uint8_t VOLUME_TABLE[VOLUME_MAX + 1] = {0, 6, 12, 22, 36, 54, 78, 108, 146, 195, 255};

// --- アラーム音 ---
const uint16_t ALARM_FREQ_HZ = 2000;
const uint32_t ALARM_BEEP_MS = 200;
const uint32_t ALARM_CYCLE_MS = 500;

// --- 時刻 ---
const char* TZ_INFO = "JST-9";
const uint32_t TIME_SYNC_GUARD_SEC = 60;  // アラーム時刻の前後この秒数はスマホからの時刻を反映しない
const time_t VALID_EPOCH_MIN = 1700000000; // これより前なら時刻未設定とみなす
// ============================================================

enum State {
  IDLE,            // 何もなし
  ARMED,           // アラームセット済み
  WAITING,         // アラーム時刻にベッドにいなかった。BED_WAIT_SEC 以内にベッドに入れば鳴らす
  RINGING,         // 鳴動中（ベッドにいる間だけ音が鳴る）
  AWAKE,           // 起床済み、二度寝監視中
  RESLEEP_ARMED,   // 二度寝検出、まもなく再鳴動
};

const char* stateName(State s) {
  switch (s) {
    case IDLE: return "IDLE";
    case ARMED: return "ARMED";
    case WAITING: return "WAITING";
    case RINGING: return "RINGING";
    case AWAKE: return "AWAKE";
    case RESLEEP_ARMED: return "RESLEEP_ARMED";
  }
  return "?";
}

// アラーム処理中（アラーム設定の変更と時刻の反映を受け付けない）
bool isAlarmActive(State s) {
  return s == RINGING || s == RESLEEP_ARMED;
}

struct WakeResult {
  uint32_t id = 0;  // 0 = まだ記録なし
  time_t ringAt = 0;
  time_t awakeAt = 0;
  int32_t durationSec = 0;
  bool resleep = false;
};

// 睡眠（ベッドに入ってから、SESSION_GAP_SEC 以上ベッドを離れるまで）。記録の管理はアプリが行う
struct SleepSession {
  bool active = false;
  time_t startAt = 0;
  time_t endAt = 0;  // 最後にベッドを出た時刻 = 起床時刻
  uint8_t resleepCount = 0;  // アラーム後にベッドに戻った回数
};

// アプリへ送る睡眠のイベント。アプリから evack が届くまで送り続ける
const char EVENT_SLEEP = 'S';    // 就寝（at = ベッドに入った時刻）
const char EVENT_RETURN = 'R';   // 二度寝（at = ベッドに戻った時刻）
const char EVENT_WAKE = 'E';     // 起床（at = 最後にベッドを出た時刻）
const int EVENT_QUEUE_SIZE = 16; // スマホとつながっていない間にためておける数（あふれたら古いものから捨てる）
struct SleepEvent {
  uint32_t id;
  char type;
  time_t at;
};

State state = IDLE;
int volumeLevel = VOLUME_DEFAULT;
time_t alarmEpoch = 0;    // 0 = 未セット（ユーザーが設定したアラーム）
time_t resleepEpoch = 0;  // 二度寝による再鳴動の時刻
time_t waitUntilEpoch = 0; // WAITING の期限

// 鳴動
time_t ringStartEpoch = 0;
uint32_t ringStartMs = 0;
bool ringIsResleep = false;
uint32_t lastBeepMs = 0;

// 起床
uint32_t awakeEnteredMs = 0;
WakeResult lastResult;
uint32_t resultCounter = 0;

// 睡眠
SleepSession curSession;   // 記録中
SleepSession lastSession;  // 最後に確定したもの（LCD 表示用）
SleepEvent eventQueue[EVENT_QUEUE_SIZE];
int eventHead = 0;  // いちばん古い未送信のイベント
int eventCount = 0;
uint32_t eventCounter = 0;
bool bedStreak = false;         // ベッドにいる（短い途切れを除く）
uint32_t bedStreakStartMs = 0;  // その開始

// 圧力
int pressureRaw = 0;
bool inBed = false;
uint32_t bedChangedMs = 0;

// BLE
BLECharacteristic* statusChar = nullptr;
volatile bool bleConnected = false;
struct CommandMsg {
  char data[200];
};
QueueHandle_t commandQueue;
uint32_t ackSeq = 0;
String ackError = "";

uint32_t lastDrawMs = 0;
uint32_t lastSerialMs = 0;
uint32_t lastNotifyMs = 0;

// ------------------------------------------------------------

bool timeIsSet() {
  return time(nullptr) >= VALID_EPOCH_MIN;
}

// LCD とアプリに出す「次に鳴る時刻」
time_t displayAlarmEpoch() {
  if (state == RESLEEP_ARMED) return resleepEpoch;
  if (state == WAITING) return waitUntilEpoch;
  return alarmEpoch;
}

// スマホをロックする（ベッドにいる間は常に、加えてアラーム処理中）
bool phoneLocked() {
  return inBed || isAlarmActive(state);
}

void setState(State next) {
  if (next == state) return;
  Serial.printf("[STATE] %s -> %s\n", stateName(state), stateName(next));
  state = next;
}

void applyVolume() {
  M5.Speaker.setVolume(VOLUME_TABLE[volumeLevel]);
}

void readPressure() {
  long sum = 0;
  for (int i = 0; i < FSR_SAMPLES; i++) sum += analogRead(FSR_PIN);
  pressureRaw = sum / FSR_SAMPLES;
  bool nowInBed = inBed ? pressureRaw >= IN_BED_THRESHOLD - IN_BED_HYSTERESIS
                        : pressureRaw >= IN_BED_THRESHOLD;
  if (nowInBed != inBed) {
    inBed = nowInBed;
    bedChangedMs = millis();
    Serial.printf("[FSR] %s (raw=%d)\n", inBed ? "IN BED" : "OUT OF BED", pressureRaw);
  }
}

uint32_t secSinceBedChange() {
  return (millis() - bedChangedMs) / 1000;
}

void startRinging(bool resleep) {
  ringStartEpoch = time(nullptr);
  ringStartMs = millis();
  ringIsResleep = resleep;
  if (resleep) {
    resleepEpoch = 0;
  } else {
    alarmEpoch = 0;
  }
  lastBeepMs = 0;
  setState(RINGING);
  Serial.printf("[ALARM] ringing (resleep=%d)\n", resleep);
}

void stopSound() {
  M5.Speaker.stop();
}

void recordWake() {
  // 圧力が消えた瞬間を起床時刻とする（時刻同期の影響を受けないよう millis で計算）
  int32_t elapsedMs = (int32_t)(bedChangedMs - ringStartMs);
  time_t awakeAt = ringStartEpoch + (elapsedMs > 0 ? elapsedMs / 1000 : 0);
  resultCounter++;
  lastResult.id = resultCounter;
  lastResult.ringAt = ringStartEpoch;
  lastResult.awakeAt = awakeAt;
  lastResult.durationSec = (int32_t)(awakeAt - ringStartEpoch);
  lastResult.resleep = ringIsResleep;
  Serial.printf("[RESULT] id=%u duration=%ds resleep=%d\n", lastResult.id,
                lastResult.durationSec, lastResult.resleep);
}

void finishRinging() {
  stopSound();
  recordWake();
  awakeEnteredMs = millis();
  setState(AWAKE);
}

// ------------------------------------------------------------
// 睡眠の記録（アラームとは関係なく、ベッドの在否だけで判定する）
// ------------------------------------------------------------

// millis() の値を時刻に直す
time_t msToEpoch(uint32_t ms) {
  return time(nullptr) - (time_t)((millis() - ms) / 1000);
}

// ベッドの在否が最後に変わった時刻
time_t bedChangedEpoch() {
  return msToEpoch(bedChangedMs);
}

void pushEvent(char type, time_t at) {
  if (eventCount == EVENT_QUEUE_SIZE) {
    Serial.println("[EVENT] queue full, dropped oldest");
    eventHead = (eventHead + 1) % EVENT_QUEUE_SIZE;
    eventCount--;
  }
  SleepEvent& e = eventQueue[(eventHead + eventCount) % EVENT_QUEUE_SIZE];
  e.id = ++eventCounter;
  e.type = type;
  e.at = at;
  eventCount++;
  Serial.printf("[EVENT] id=%u %c %s\n", e.id, type, fmtTime(at).c_str());
}

// アプリが受け取ったイベントを消す
void ackEvent(uint32_t id) {
  if (eventCount > 0 && eventQueue[eventHead].id == id) {
    eventHead = (eventHead + 1) % EVENT_QUEUE_SIZE;
    eventCount--;
  }
}

void startSession(time_t at) {
  curSession = SleepSession();
  curSession.active = true;
  curSession.startAt = at;
  pushEvent(EVENT_SLEEP, at);
}

void updateSession() {
  // ベッドにいる連続時間。SENSOR_NOISE_SEC 未満の途切れはセンサーの誤差として無視する
  if (inBed) {
    if (!bedStreak) {
      bedStreak = true;
      bedStreakStartMs = bedChangedMs;
    }
  } else if (bedStreak && secSinceBedChange() >= SENSOR_NOISE_SEC) {
    bedStreak = false;
  }

  if (!curSession.active) {
    // SLEEP_START_CONFIRM_SEC 続いたら睡眠とみなし、ベッドに入った時刻までさかのぼって開始する
    if (bedStreak && timeIsSet() && (millis() - bedStreakStartMs) / 1000 >= SLEEP_START_CONFIRM_SEC) {
      startSession(msToEpoch(bedStreakStartMs));
    }
    return;
  }
  // SESSION_GAP_SEC 以内に戻れば続いているので何もしない
  if (!inBed && secSinceBedChange() >= SESSION_GAP_SEC) {
    curSession.endAt = bedChangedEpoch();
    lastSession = curSession;
    curSession = SleepSession();
    pushEvent(EVENT_WAKE, lastSession.endAt);
  }
}

// アラーム後にベッドに戻った（二度寝）
// 記録中の睡眠がなければ（アラーム前の在床が SLEEP_START_CONFIRM_SEC 未満だった）、戻った時刻から記録を始める
void onReturnToBed(time_t at) {
  if (!curSession.active) startSession(at);
  curSession.resleepCount++;
  pushEvent(EVENT_RETURN, at);
}

void leaveAwake() {
  setState(alarmEpoch > 0 ? ARMED : IDLE);
}

// ユーザーが設定したアラーム時刻になった。ベッドにいなければ BED_WAIT_SEC 待つ
void onAlarmDue() {
  resleepEpoch = 0;
  if (inBed) {
    startRinging(false);
  } else {
    waitUntilEpoch = alarmEpoch + BED_WAIT_SEC;
    alarmEpoch = 0;
    Serial.printf("[ALARM] not in bed, waiting until %s\n", fmtTime(waitUntilEpoch).c_str());
    setState(WAITING);
  }
}

void updateState() {
  uint32_t now = millis();
  time_t epoch = time(nullptr);
  bool alarmDue = alarmEpoch > 0 && epoch >= alarmEpoch;

  switch (state) {
    case IDLE:
    case ARMED:
      if (alarmDue) onAlarmDue();
      break;

    case WAITING:
      if (inBed) {
        startRinging(false);
      } else if (epoch >= waitUntilEpoch) {
        Serial.println("[ALARM] skipped (did not get into bed)");
        waitUntilEpoch = 0;
        leaveAwake();
      }
      break;

    case RINGING:
      // 音はベッドにいる間だけ。出た瞬間に止め、戻ったらまた鳴らす
      if (inBed) {
        if (now - lastBeepMs >= ALARM_CYCLE_MS) {
          lastBeepMs = now;
          M5.Speaker.tone(ALARM_FREQ_HZ, ALARM_BEEP_MS);
        }
      } else {
        if (M5.Speaker.isPlaying()) stopSound();
        if (secSinceBedChange() >= WAKE_CONFIRM_SEC) finishRinging();
      }
      break;

    case AWAKE: {
      // ベッドに戻った時刻が、起床から RESLEEP_WINDOW_SEC 以内かどうか
      bool returnedInWindow = inBed && bedChangedMs - awakeEnteredMs < RESLEEP_WINDOW_SEC * 1000UL;
      if (alarmDue) {
        onAlarmDue();
      } else if (returnedInWindow && secSinceBedChange() >= RESLEEP_DETECT_SEC) {
        // 鳴らすのは「ベッドに戻ってから」RESLEEP_ALARM_DELAY_SEC 後
        onReturnToBed(epoch - secSinceBedChange());
        resleepEpoch = epoch - secSinceBedChange() + RESLEEP_ALARM_DELAY_SEC;
        Serial.printf("[RESLEEP] detected, alarm at %s\n", fmtTime(resleepEpoch).c_str());
        setState(RESLEEP_ARMED);
      } else if (inBed && !returnedInWindow) {
        leaveAwake();  // 監視時間を過ぎてから戻った（ロックはされる）
      } else if (!inBed && now - awakeEnteredMs >= RESLEEP_WINDOW_SEC * 1000UL) {
        leaveAwake();
      }
      break;
    }

    case RESLEEP_ARMED:
      if (alarmDue) {
        onAlarmDue();
      } else if (epoch >= resleepEpoch) {
        startRinging(true);
      } else if (!inBed && secSinceBedChange() >= WAKE_CONFIRM_SEC) {
        // 鳴る前に起きたので取り消す（監視時間は最初の起床から数え続ける）
        Serial.println("[RESLEEP] cancelled (out of bed)");
        resleepEpoch = 0;
        setState(AWAKE);
      }
      break;
  }
}

void handleButtons() {
  if (M5.BtnA.wasPressed() && volumeLevel < VOLUME_MAX) {
    volumeLevel++;
    applyVolume();
    if (state != RINGING) M5.Speaker.tone(ALARM_FREQ_HZ, 80);
    Serial.printf("[VOLUME] %d\n", volumeLevel);
  }
  if (M5.BtnB.wasPressed() && volumeLevel > VOLUME_MIN) {
    volumeLevel--;
    applyVolume();
    if (state != RINGING) M5.Speaker.tone(ALARM_FREQ_HZ, 80);
    Serial.printf("[VOLUME] %d\n", volumeLevel);
  }
  if (M5.BtnC.wasPressed() && state == RINGING) {
    if (inBed) {
      Serial.println("[BUTTON] ignored (still in bed)");
    } else {
      Serial.println("[BUTTON] alarm stopped");
      finishRinging();
    }
  }
}

// ------------------------------------------------------------
// 時刻（スマホから受け取る。アラーム処理と重ならないようにする）
// ------------------------------------------------------------

// 鳴動中やアラーム時刻の直前直後は時刻を動かさない
bool isTimeSyncSafe() {
  if (isAlarmActive(state) || state == WAITING) return false;
  if (alarmEpoch > 0) {
    long diff = (long)(alarmEpoch - time(nullptr));
    if (diff >= -(long)TIME_SYNC_GUARD_SEC && diff <= (long)TIME_SYNC_GUARD_SEC) return false;
  }
  return true;
}

void applyPhoneTime(time_t epoch) {
  if (epoch < VALID_EPOCH_MIN) return;
  if (timeIsSet() && !isTimeSyncSafe()) {
    Serial.println("[TIME] sync postponed (alarm in progress)");
    return;
  }
  struct timeval tv = {epoch, 0};
  settimeofday(&tv, nullptr);
  Serial.printf("[TIME] set from phone: %s\n", fmtTime(epoch).c_str());
}

// ------------------------------------------------------------
// 画面
// ------------------------------------------------------------

String fmtTime(time_t t) {
  if (t <= 0) return "--:--";
  struct tm tmv;
  localtime_r(&t, &tmv);
  char buf[16];
  snprintf(buf, sizeof(buf), "%02d:%02d:%02d", tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
  return String(buf);
}

void drawScreen() {
  auto& d = M5.Display;
  d.startWrite();
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.setTextSize(2);
  d.setCursor(0, 0);
  d.printf("%-26s\n", timeIsSet() ? fmtTime(time(nullptr)).c_str() : "time not set");
  d.setTextColor(phoneLocked() ? TFT_RED : TFT_GREEN, TFT_BLACK);
  d.printf("%-14s%-12s\n", stateName(state), phoneLocked() ? "LOCK" : "");
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.printf("Alarm : %-18s\n", fmtTime(displayAlarmEpoch()).c_str());
  d.printf("FSR   : %-4d %-12s\n", pressureRaw, inBed ? "IN BED" : "OUT");
  if (lastResult.id > 0) {
    d.printf("Last  : %ds%-14s\n", lastResult.durationSec, lastResult.resleep ? " (resleep)" : "");
  } else {
    d.printf("%-26s\n", "Last  : -");
  }
  if (curSession.active) {
    d.printf("Sleep : %.5s-       x%-4u\n", fmtTime(curSession.startAt).c_str(), curSession.resleepCount);
  } else if (lastSession.startAt > 0) {
    d.printf("Sleep : %.5s-%.5s x%-4u\n", fmtTime(lastSession.startAt).c_str(),
             fmtTime(lastSession.endAt).c_str(), lastSession.resleepCount);
  } else {
    d.printf("%-26s\n", "Sleep : -");
  }
  d.printf("BLE   : %-18s\n", bleConnected ? "connected" : "waiting");

  // 音量（大きく表示）
  d.setTextSize(4);
  d.setCursor(0, 150);
  d.setTextColor(TFT_YELLOW, TFT_BLACK);
  d.printf("VOL %2d", volumeLevel);

  // ボタンラベル
  d.setTextSize(2);
  d.setTextColor(TFT_CYAN, TFT_BLACK);
  d.setCursor(40, 220);
  d.print("UP");
  d.setCursor(130, 220);
  d.print("DOWN");
  d.setCursor(230, 220);
  d.print("STOP");
  d.endWrite();
}

// ------------------------------------------------------------
// BLE
//   状態: STATUS を read / notify（JSON）
//   コマンド: COMMAND に JSON を write
//     {"cmd":"time","epoch":<unix秒>}            時刻を合わせる
//     {"seq":n,"cmd":"set","epoch":<unix秒>}     アラームをセット
//     {"seq":n,"cmd":"cancel"}                   アラームを解除
//     {"cmd":"evack","id":n}                     睡眠イベント n を受け取った（次のイベントを送る）
//   seq 付きのコマンドの結果は、状態 JSON の ack に入る
// ------------------------------------------------------------

// 状態 JSON は notify 1回（MTU 247 なら 244 バイト）に収まるよう短いキーにする
//   s=状態 l=ロック a=次に鳴る時刻 v=音量 t=現在時刻 r=raw b=ベッドにいる
//   w=[id, ringAt, awakeAt, durationSec, resleep]（記録なしは null） k=[seq, error]
//   e=[id, 種類(S=就寝 R=二度寝 E=起床), 時刻]（未送信でいちばん古い睡眠イベント。なしは null）
String buildStatusJson() {
  JsonDocument doc;
  doc["s"] = stateName(state);
  doc["l"] = phoneLocked() ? 1 : 0;
  doc["a"] = (long)displayAlarmEpoch();
  doc["v"] = volumeLevel;
  doc["t"] = timeIsSet() ? (long)time(nullptr) : 0L;
  doc["r"] = pressureRaw;
  doc["b"] = inBed ? 1 : 0;
  if (lastResult.id > 0) {
    JsonArray w = doc["w"].to<JsonArray>();
    w.add(lastResult.id);
    w.add((long)lastResult.ringAt);
    w.add((long)lastResult.awakeAt);
    w.add(lastResult.durationSec);
    w.add(lastResult.resleep ? 1 : 0);
  } else {
    doc["w"] = nullptr;
  }
  if (eventCount > 0) {
    const SleepEvent& ev = eventQueue[eventHead];
    char type[2] = {ev.type, '\0'};
    JsonArray e = doc["e"].to<JsonArray>();
    e.add(ev.id);
    e.add(type);
    e.add((long)ev.at);
  } else {
    doc["e"] = nullptr;
  }
  JsonArray k = doc["k"].to<JsonArray>();
  k.add(ackSeq);
  k.add(ackError);
  String body;
  serializeJson(doc, body);
  return body;
}

void notifyStatus() {
  String body = buildStatusJson();
  if (body.length() > 244) Serial.printf("[BLE] status too long: %u bytes\n", body.length());
  statusChar->setValue(body);
  if (bleConnected) statusChar->notify();
  lastNotifyMs = millis();
}

const char* doSetAlarm(time_t epoch) {
  if (isAlarmActive(state)) return "locked";
  if (!timeIsSet()) return "time not set";
  if (epoch <= time(nullptr)) return "epoch is in the past";
  alarmEpoch = epoch;
  if (state == IDLE) setState(ARMED);
  Serial.printf("[BLE] alarm set %s\n", fmtTime(alarmEpoch).c_str());
  return "";
}

const char* doCancelAlarm() {
  if (isAlarmActive(state)) return "locked";
  alarmEpoch = 0;
  waitUntilEpoch = 0;
  if (state == ARMED || state == WAITING) setState(IDLE);
  Serial.println("[BLE] alarm cancelled");
  return "";
}

void handleCommand(const char* text) {
  JsonDocument req;
  if (deserializeJson(req, text)) {
    Serial.printf("[BLE] bad command: %s\n", text);
    return;
  }
  uint32_t seq = req["seq"] | 0;
  const char* cmd = req["cmd"] | "";
  const char* err = "";
  if (strcmp(cmd, "time") == 0) {
    applyPhoneTime(req["epoch"].as<long>());
  } else if (strcmp(cmd, "set") == 0) {
    err = doSetAlarm(req["epoch"].as<long>());
  } else if (strcmp(cmd, "evack") == 0) {
    ackEvent(req["id"].as<uint32_t>());
  } else if (strcmp(cmd, "cancel") == 0) {
    err = doCancelAlarm();
  } else {
    err = "unknown command";
  }
  if (seq != 0) {
    ackSeq = seq;
    ackError = err;
  }
  notifyStatus();
}

// BLE のコールバックは別タスクで呼ばれるので、コマンドはキューに入れて loop() で処理する
class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    CommandMsg msg;
    size_t n = min((size_t)v.length(), sizeof(msg.data) - 1);
    memcpy(msg.data, v.c_str(), n);
    msg.data[n] = '\0';
    xQueueSend(commandQueue, &msg, 0);
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    bleConnected = true;
    Serial.println("[BLE] connected");
  }
  void onDisconnect(BLEServer*) override {
    bleConnected = false;
    Serial.println("[BLE] disconnected");
    BLEDevice::startAdvertising();
  }
};

void setupBle() {
  commandQueue = xQueueCreate(8, sizeof(CommandMsg));
  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(517);  // 状態 JSON を1回の notify で送れるようにする
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(SERVICE_UUID);
  statusChar = service->createCharacteristic(
      STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  statusChar->addDescriptor(new BLE2902());
  BLECharacteristic* commandChar =
      service->createCharacteristic(COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE);
  commandChar->setCallbacks(new CommandCallbacks());
  service->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);  // 名前は scan response に入れる（広告パケットの31バイト制限）
  BLEDevice::startAdvertising();
  notifyStatus();
  Serial.println("[BLE] advertising");
}

// ------------------------------------------------------------

void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  Serial.begin(115200);
  M5.Display.fillScreen(TFT_BLACK);
  M5.Speaker.begin();
  applyVolume();

  setenv("TZ", TZ_INFO, 1);
  tzset();

  analogReadResolution(12);
  analogSetPinAttenuation(FSR_PIN, ADC_11db);
  readPressure();
  bedChangedMs = millis();

  setupBle();
  Serial.println("[BOOT] ready");
}

void loop() {
  M5.update();
  CommandMsg msg;
  while (xQueueReceive(commandQueue, &msg, 0) == pdTRUE) handleCommand(msg.data);
  readPressure();
  handleButtons();
  updateState();
  updateSession();

  uint32_t now = millis();
  if (now - lastNotifyMs >= STATUS_NOTIFY_MS) notifyStatus();
  if (now - lastDrawMs >= 500) {
    lastDrawMs = now;
    drawScreen();
  }
  if (now - lastSerialMs >= 1000) {
    lastSerialMs = now;
    Serial.printf("[DBG] %s raw=%d inBed=%d lock=%d vol=%d alarm=%s ble=%d\n", stateName(state),
                  pressureRaw, inBed, phoneLocked(), volumeLevel, fmtTime(alarmEpoch).c_str(),
                  bleConnected);
  }
  delay(10);
}
