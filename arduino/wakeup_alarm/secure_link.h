/*
 * スマホとの BLE 通信の暗号化（QR ペアリング + ECDH + AES-256-GCM）
 *
 * ペアリング: LCD の QR（devId・デバイス公開鍵・使い捨てトークン T）をスマホが読む
 *   スマホ → 0x01 | phonePub(65) | phoneId(8) | HMAC(T, phonePub||phoneId)(32)
 *   M5    → 0x11 | 結果(1)                       ※ K = HKDF(ECDH, salt=T, "wakeup-v1"||devId||phoneId)
 * 接続ごと:
 *   M5    → 0x10 | devId(4) | nonceD(16) | flags(1)   認証前はこれだけを notify する
 *   スマホ → 0x02 | phoneId(8) | nonceP(16) | HMAC(K, "auth"||nonceD||nonceP)(32)
 *   M5    → 0x12                                     認証失敗（登録なし・HMAC 不一致）
 *   以降   0x20 | ctr(4) | AES-256-GCM(SK, JSON) | tag(16)   SK = HKDF(K, nonceD||nonceP, "session")
 *          IV = dir(1) | 0(7) | ctr(4)、AAD = 先頭5バイト、ctr は方向ごとに単調増加
 */
#pragma once

#include <Arduino.h>

namespace SecureLink {

const uint8_t FRAME_PAIR = 0x01;
const uint8_t FRAME_AUTH = 0x02;
const uint8_t FRAME_HELLO = 0x10;
const uint8_t FRAME_PAIR_RESULT = 0x11;
const uint8_t FRAME_AUTH_FAIL = 0x12;
const uint8_t FRAME_DATA = 0x20;

const uint8_t PAIR_OK = 0;
const uint8_t PAIR_NOT_PAIRING = 1;  // ペアリングモードではない
const uint8_t PAIR_BAD_TOKEN = 2;    // QR のトークンと合わない
const uint8_t PAIR_ERROR = 3;

const uint32_t PAIRING_SEC = 120;       // ペアリングモードの長さ
const int PAIRING_MAX_FAILURES = 3;     // これだけ失敗したらペアリングモードを打ち切る
const int MAX_PHONES = 4;               // 登録できるスマホの数
const size_t OVERHEAD = 1 + 4 + 16;     // DATA フレームの暗号化で増えるバイト数

enum Result {
  IGNORED,    // 何もしない
  PLAINTEXT,  // 復号できた。plain に JSON が入っている
  PAIRED,     // ペアリング成功
  AUTHED,     // 認証成功
  REJECTED,   // 拒否（不正なフレーム）。返すフレームがあれば reply に入る
};

// NVS から鍵を読み込む。鍵がなければ false（createKey() で作る）
bool begin();
// 鍵を作って NVS に保存する。真の乱数を使うため BLE を初期化した後に呼ぶ
bool createKey();
const char* devId();  // 8桁の16進数

// ペアリングモード
void startPairing();
void stopPairing();
bool pairing();
uint32_t pairingRemainingSec();
String qrText();
void update();  // ペアリングモードの時間切れ

int pairedCount();
uint32_t blockedCount();  // 拒否したフレームの数（電源を入れてから）
void clearAll();          // 全スマホの登録を消す

// 接続ごとの状態
void resetSession();  // 接続・切断のたびに呼ぶ（新しい nonce、未認証に戻す）
bool authenticated();
bool unpairCurrent();  // いま認証しているスマホの登録を消す

size_t buildHello(uint8_t* out, size_t cap);
// 受け取ったフレームを処理する。reply にスマホへ返すフレームが入ることがある（replyLen > 0）
Result handleFrame(const uint8_t* in, size_t len, char* plain, size_t plainCap,
                   uint8_t* reply, size_t replyCap, size_t* replyLen);
// JSON を暗号化して DATA フレームにする。失敗したら 0
size_t seal(const char* json, uint8_t* out, size_t cap);

}  // namespace SecureLink
