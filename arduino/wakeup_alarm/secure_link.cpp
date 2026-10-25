#include "secure_link.h"

#include <Preferences.h>
#include <esp_random.h>
#include <mbedtls/ecdh.h>
#include <mbedtls/ecp.h>
#include <mbedtls/gcm.h>
#include <mbedtls/hkdf.h>
#include <mbedtls/md.h>
#include <mbedtls/platform_util.h>

namespace SecureLink {

namespace {

const size_t PUB_LEN = 65;  // 非圧縮の P-256 公開鍵
const size_t PRIV_LEN = 32;
const size_t TOKEN_LEN = 16;
const size_t PHONE_ID_LEN = 8;
const size_t KEY_LEN = 32;
const size_t NONCE_LEN = 16;
const size_t MAC_LEN = 32;
const size_t TAG_LEN = 16;
const uint8_t DIR_TO_PHONE = 0x01;
const uint8_t DIR_FROM_PHONE = 0x02;

struct Slot {
  uint8_t phoneId[PHONE_ID_LEN];
  uint8_t key[KEY_LEN];
};

Preferences store;
uint8_t devPriv[PRIV_LEN];
uint8_t devPub[PUB_LEN];
uint8_t devIdRaw[4];
char devIdHex[9];

Slot slots[MAX_PHONES];
bool slotUsed[MAX_PHONES];

bool pairingMode = false;
uint32_t pairingStartMs = 0;
int pairingFailures = 0;
uint8_t pairingToken[TOKEN_LEN];

uint32_t blocked = 0;

// 接続ごと
uint8_t nonceD[NONCE_LEN];
bool authed = false;
int authedSlot = -1;
uint8_t sessionKey[KEY_LEN];
uint32_t txCounter = 0;
uint32_t rxCounter = 0;

// ESP32 の乱数は BLE（RF）が動いていれば真の乱数になる
int rng(void*, unsigned char* buf, size_t len) {
  esp_fill_random(buf, len);
  return 0;
}

bool hmac(const uint8_t* key, size_t keyLen, const uint8_t* a, size_t aLen, const uint8_t* b, size_t bLen,
          const uint8_t* c, size_t cLen, uint8_t out[MAC_LEN]) {
  const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  bool ok = mbedtls_md_setup(&ctx, md, 1) == 0 && mbedtls_md_hmac_starts(&ctx, key, keyLen) == 0 &&
            mbedtls_md_hmac_update(&ctx, a, aLen) == 0 && (bLen == 0 || mbedtls_md_hmac_update(&ctx, b, bLen) == 0) &&
            (cLen == 0 || mbedtls_md_hmac_update(&ctx, c, cLen) == 0) && mbedtls_md_hmac_finish(&ctx, out) == 0;
  mbedtls_md_free(&ctx);
  return ok;
}

bool hkdf(const uint8_t* salt, size_t saltLen, const uint8_t* ikm, size_t ikmLen, const uint8_t* info, size_t infoLen,
          uint8_t out[KEY_LEN]) {
  return mbedtls_hkdf(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), salt, saltLen, ikm, ikmLen, info, infoLen, out,
                      KEY_LEN) == 0;
}

// 比較にかかる時間で一致したバイト数が分からないようにする
bool equalsCt(const uint8_t* a, const uint8_t* b, size_t len) {
  uint8_t diff = 0;
  for (size_t i = 0; i < len; i++) diff |= a[i] ^ b[i];
  return diff == 0;
}

bool computePublic() {
  mbedtls_ecp_keypair kp;
  mbedtls_ecp_keypair_init(&kp);
  size_t olen = 0;
  bool ok = mbedtls_ecp_read_key(MBEDTLS_ECP_DP_SECP256R1, &kp, devPriv, PRIV_LEN) == 0 &&
            mbedtls_ecp_keypair_calc_public(&kp, rng, nullptr) == 0 &&
            mbedtls_ecp_write_public_key(&kp, MBEDTLS_ECP_PF_UNCOMPRESSED, &olen, devPub, PUB_LEN) == 0 &&
            olen == PUB_LEN;
  mbedtls_ecp_keypair_free(&kp);
  return ok;
}

bool generateKey() {
  mbedtls_ecp_keypair kp;
  mbedtls_ecp_keypair_init(&kp);
  size_t olen = 0;
  bool ok = mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, &kp, rng, nullptr) == 0 &&
            mbedtls_ecp_write_key_ext(&kp, &olen, devPriv, PRIV_LEN) == 0 && olen == PRIV_LEN;
  mbedtls_ecp_keypair_free(&kp);
  return ok;
}

// スマホの公開鍵との ECDH
bool ecdh(const uint8_t* phonePub, uint8_t out[32]) {
  mbedtls_ecp_group grp;
  mbedtls_ecp_point q;
  mbedtls_mpi d, z;
  mbedtls_ecp_group_init(&grp);
  mbedtls_ecp_point_init(&q);
  mbedtls_mpi_init(&d);
  mbedtls_mpi_init(&z);
  bool ok = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
            mbedtls_ecp_point_read_binary(&grp, &q, phonePub, PUB_LEN) == 0 &&
            mbedtls_ecp_check_pubkey(&grp, &q) == 0 &&  // 曲線上にない点（無効曲線攻撃）を拒否
            mbedtls_mpi_read_binary(&d, devPriv, PRIV_LEN) == 0 &&
            mbedtls_ecdh_compute_shared(&grp, &z, &q, &d, rng, nullptr) == 0 &&
            mbedtls_mpi_write_binary(&z, out, 32) == 0;
  mbedtls_mpi_free(&z);
  mbedtls_mpi_free(&d);
  mbedtls_ecp_point_free(&q);
  mbedtls_ecp_group_free(&grp);
  return ok;
}

void slotName(int i, char* buf) {
  snprintf(buf, 4, "p%d", i);
}

void loadSlots() {
  char name[4];
  for (int i = 0; i < MAX_PHONES; i++) {
    slotName(i, name);
    slotUsed[i] = store.getBytes(name, &slots[i], sizeof(Slot)) == sizeof(Slot);
  }
}

int findSlot(const uint8_t* phoneId) {
  for (int i = 0; i < MAX_PHONES; i++) {
    if (slotUsed[i] && memcmp(slots[i].phoneId, phoneId, PHONE_ID_LEN) == 0) return i;
  }
  return -1;
}

// 同じ phoneId は上書き、空きがなければいちばん番号の小さい枠を上書きする
int saveSlot(const uint8_t* phoneId, const uint8_t* key) {
  int i = findSlot(phoneId);
  for (int j = 0; i < 0 && j < MAX_PHONES; j++) {
    if (!slotUsed[j]) i = j;
  }
  if (i < 0) i = 0;
  memcpy(slots[i].phoneId, phoneId, PHONE_ID_LEN);
  memcpy(slots[i].key, key, KEY_LEN);
  slotUsed[i] = true;
  char name[4];
  slotName(i, name);
  store.putBytes(name, &slots[i], sizeof(Slot));
  return i;
}

void removeSlot(int i) {
  char name[4];
  slotName(i, name);
  store.remove(name);
  mbedtls_platform_zeroize(&slots[i], sizeof(Slot));
  slotUsed[i] = false;
}

String base64url(const uint8_t* data, size_t len) {
  static const char* table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  String out;
  for (size_t i = 0; i < len; i += 3) {
    uint32_t n = (uint32_t)data[i] << 16;
    if (i + 1 < len) n |= (uint32_t)data[i + 1] << 8;
    if (i + 2 < len) n |= data[i + 2];
    out += table[(n >> 18) & 63];
    out += table[(n >> 12) & 63];
    if (i + 1 < len) out += table[(n >> 6) & 63];
    if (i + 2 < len) out += table[n & 63];
  }
  return out;
}

void makeIv(uint8_t dir, uint32_t ctr, uint8_t iv[12]) {
  memset(iv, 0, 12);
  iv[0] = dir;
  iv[8] = ctr >> 24;
  iv[9] = ctr >> 16;
  iv[10] = ctr >> 8;
  iv[11] = ctr;
}

Result reject(const char* why) {
  blocked++;
  Serial.printf("[SEC] rejected: %s (total %u)\n", why, blocked);
  return REJECTED;
}

Result handlePair(const uint8_t* in, size_t len, uint8_t* reply, size_t* replyLen) {
  reply[0] = FRAME_PAIR_RESULT;
  *replyLen = 2;
  if (!pairingMode) {
    reply[1] = PAIR_NOT_PAIRING;
    return reject("pair outside pairing mode");
  }
  if (len != 1 + PUB_LEN + PHONE_ID_LEN + MAC_LEN) {
    reply[1] = PAIR_ERROR;
    return reject("bad pair frame");
  }
  const uint8_t* phonePub = in + 1;
  const uint8_t* phoneId = phonePub + PUB_LEN;
  const uint8_t* mac = phoneId + PHONE_ID_LEN;
  uint8_t expected[MAC_LEN];
  if (!hmac(pairingToken, TOKEN_LEN, phonePub, PUB_LEN, phoneId, PHONE_ID_LEN, nullptr, 0, expected) ||
      !equalsCt(expected, mac, MAC_LEN)) {
    reply[1] = PAIR_BAD_TOKEN;
    if (++pairingFailures >= PAIRING_MAX_FAILURES) {
      Serial.println("[SEC] too many pairing failures");
      stopPairing();
    }
    return reject("pair token mismatch");
  }
  uint8_t z[32];
  uint8_t info[9 + 4 + PHONE_ID_LEN];
  memcpy(info, "wakeup-v1", 9);
  memcpy(info + 9, devIdRaw, 4);
  memcpy(info + 13, phoneId, PHONE_ID_LEN);
  uint8_t key[KEY_LEN];
  bool ok = ecdh(phonePub, z) && hkdf(pairingToken, TOKEN_LEN, z, sizeof(z), info, sizeof(info), key);
  mbedtls_platform_zeroize(z, sizeof(z));
  if (!ok) {
    reply[1] = PAIR_ERROR;
    return reject("bad phone public key");
  }
  int i = saveSlot(phoneId, key);
  mbedtls_platform_zeroize(key, sizeof(key));
  stopPairing();
  reply[1] = PAIR_OK;
  Serial.printf("[SEC] paired phone in slot %d\n", i);
  return PAIRED;
}

Result handleAuth(const uint8_t* in, size_t len, uint8_t* reply, size_t* replyLen) {
  if (len != 1 + PHONE_ID_LEN + NONCE_LEN + MAC_LEN) return reject("bad auth frame");
  const uint8_t* phoneId = in + 1;
  const uint8_t* nonceP = phoneId + PHONE_ID_LEN;
  const uint8_t* mac = nonceP + NONCE_LEN;
  int i = findSlot(phoneId);
  uint8_t expected[MAC_LEN];
  if (i < 0 || !hmac(slots[i].key, KEY_LEN, (const uint8_t*)"auth", 4, nonceD, NONCE_LEN, nonceP, NONCE_LEN, expected) ||
      !equalsCt(expected, mac, MAC_LEN)) {
    reply[0] = FRAME_AUTH_FAIL;
    *replyLen = 1;
    return reject(i < 0 ? "unknown phone" : "auth mac mismatch");
  }
  uint8_t salt[NONCE_LEN * 2];
  memcpy(salt, nonceD, NONCE_LEN);
  memcpy(salt + NONCE_LEN, nonceP, NONCE_LEN);
  if (!hkdf(salt, sizeof(salt), slots[i].key, KEY_LEN, (const uint8_t*)"session", 7, sessionKey)) {
    return reject("session key");
  }
  authed = true;
  authedSlot = i;
  txCounter = 0;
  rxCounter = 0;
  Serial.printf("[SEC] phone in slot %d authenticated\n", i);
  return AUTHED;
}

Result handleData(const uint8_t* in, size_t len, char* plain, size_t plainCap) {
  if (!authed) return reject("data before auth");
  if (len < OVERHEAD) return reject("short data frame");
  size_t bodyLen = len - OVERHEAD;
  if (bodyLen + 1 > plainCap) return reject("data too long");
  uint32_t ctr = ((uint32_t)in[1] << 24) | ((uint32_t)in[2] << 16) | ((uint32_t)in[3] << 8) | in[4];
  if (ctr <= rxCounter) return reject("replayed counter");
  uint8_t iv[12];
  makeIv(DIR_FROM_PHONE, ctr, iv);
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, sessionKey, KEY_LEN * 8) == 0 &&
            mbedtls_gcm_auth_decrypt(&gcm, bodyLen, iv, sizeof(iv), in, 5, in + len - TAG_LEN, TAG_LEN, in + 5,
                                     (uint8_t*)plain) == 0;
  mbedtls_gcm_free(&gcm);
  if (!ok) return reject("data tag mismatch");
  rxCounter = ctr;
  plain[bodyLen] = '\0';
  return PLAINTEXT;
}

}  // namespace

bool createKey() {
  store.begin("sec", false);
  Serial.println("[SEC] generating device key");
  bool ok = generateKey() && computePublic();
  if (ok) store.putBytes("key", devPriv, PRIV_LEN);
  mbedtls_platform_zeroize(devPriv, PRIV_LEN);
  store.end();
  return ok;
}

bool begin() {
  store.begin("sec", false);
  if (store.getBytes("key", devPriv, PRIV_LEN) != PRIV_LEN || !computePublic()) {
    Serial.println("[SEC] no device key");
    store.end();
    return false;
  }
  uint8_t hash[32];
  mbedtls_md(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), devPub, PUB_LEN, hash);
  memcpy(devIdRaw, hash, 4);
  snprintf(devIdHex, sizeof(devIdHex), "%02x%02x%02x%02x", hash[0], hash[1], hash[2], hash[3]);
  loadSlots();
  resetSession();
  Serial.printf("[SEC] devId=%s paired=%d\n", devIdHex, pairedCount());
  return true;
}

const char* devId() {
  return devIdHex;
}

void startPairing() {
  esp_fill_random(pairingToken, TOKEN_LEN);
  pairingMode = true;
  pairingStartMs = millis();
  pairingFailures = 0;
  Serial.println("[SEC] pairing mode");
}

void stopPairing() {
  if (!pairingMode) return;
  pairingMode = false;
  mbedtls_platform_zeroize(pairingToken, TOKEN_LEN);
  Serial.println("[SEC] pairing mode ended");
}

bool pairing() {
  return pairingMode;
}

uint32_t pairingRemainingSec() {
  if (!pairingMode) return 0;
  uint32_t elapsed = (millis() - pairingStartMs) / 1000;
  return elapsed >= PAIRING_SEC ? 0 : PAIRING_SEC - elapsed;
}

String qrText() {
  return String("WAKEUP1:") + devIdHex + ":" + base64url(devPub, PUB_LEN) + ":" + base64url(pairingToken, TOKEN_LEN);
}

void update() {
  if (pairingMode && pairingRemainingSec() == 0) stopPairing();
}

int pairedCount() {
  int n = 0;
  for (int i = 0; i < MAX_PHONES; i++) n += slotUsed[i] ? 1 : 0;
  return n;
}

uint32_t blockedCount() {
  return blocked;
}

void clearAll() {
  for (int i = 0; i < MAX_PHONES; i++) {
    if (slotUsed[i]) removeSlot(i);
  }
  resetSession();
  Serial.println("[SEC] all phones removed");
}

void resetSession() {
  esp_fill_random(nonceD, NONCE_LEN);
  authed = false;
  authedSlot = -1;
  mbedtls_platform_zeroize(sessionKey, KEY_LEN);
  txCounter = 0;
  rxCounter = 0;
}

bool authenticated() {
  return authed;
}

bool unpairCurrent() {
  if (!authed || authedSlot < 0) return false;
  removeSlot(authedSlot);
  resetSession();
  Serial.println("[SEC] phone unpaired");
  return true;
}

size_t buildHello(uint8_t* out, size_t cap) {
  if (cap < 1 + 4 + NONCE_LEN + 1) return 0;
  out[0] = FRAME_HELLO;
  memcpy(out + 1, devIdRaw, 4);
  memcpy(out + 5, nonceD, NONCE_LEN);
  out[5 + NONCE_LEN] = pairingMode ? 1 : 0;
  return 1 + 4 + NONCE_LEN + 1;
}

Result handleFrame(const uint8_t* in, size_t len, char* plain, size_t plainCap, uint8_t* reply, size_t replyCap,
                   size_t* replyLen) {
  *replyLen = 0;
  if (len == 0 || replyCap < 2) return reject("empty frame");
  switch (in[0]) {
    case FRAME_PAIR: return handlePair(in, len, reply, replyLen);
    case FRAME_AUTH: return handleAuth(in, len, reply, replyLen);
    case FRAME_DATA: return handleData(in, len, plain, plainCap);
    default: return reject("unknown frame (plaintext command?)");
  }
}

size_t seal(const char* json, uint8_t* out, size_t cap) {
  if (!authed) return 0;
  size_t bodyLen = strlen(json);
  if (bodyLen + OVERHEAD > cap) return 0;
  uint32_t ctr = ++txCounter;
  out[0] = FRAME_DATA;
  out[1] = ctr >> 24;
  out[2] = ctr >> 16;
  out[3] = ctr >> 8;
  out[4] = ctr;
  uint8_t iv[12];
  makeIv(DIR_TO_PHONE, ctr, iv);
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, sessionKey, KEY_LEN * 8) == 0 &&
            mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, bodyLen, iv, sizeof(iv), out, 5,
                                      (const uint8_t*)json, out + 5, TAG_LEN, out + 5 + bodyLen) == 0;
  mbedtls_gcm_free(&gcm);
  return ok ? bodyLen + OVERHEAD : 0;
}

}  // namespace SecureLink
