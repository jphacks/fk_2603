# 二度寝防止アラーム

[![IMAGE ALT TEXT HERE](https://jphacks.com/wp-content/uploads/2026/07/IMG_5316.png)](https://www.youtube.com/watch?v=piaNsc6ilBI)

## 製品概要
### 背景(製品開発のきっかけ、課題等）
目覚ましを止めたあと、そのまま布団の中でスマホを触ったり二度寝したりしてしまい、結局起きられないことが多い。アラームを「止める」ことはできても「起きる」ことは強制できないのが課題。

### 製品説明（具体的な製品の説明）
ベッド/枕の下に圧力センサを置き、「ベッドから出たかどうか」で起床を判定する目覚ましシステム。
アラームはベッド脇の M5Stack から鳴り、スマホアプリから Bluetooth (BLE) でアラーム時刻をセットする。
アラームはベッドにいる間だけ鳴り、ベッドを出ると自動で止まる。アラーム時刻にベッドにいなければ、10 分以内にベッドに入ったときに鳴る。ベッドの上にいる間はいつでもスマホがロックされて使えない。起きた後 20 分以内にベッドへ戻ると二度寝とみなし、戻ってから 5 分後にアラームを自動で鳴らす。

### 特長
#### 1. ベッドの上ではスマホが使えない
圧力センサが「ベッドにいる」と判定している間は、アラームに関係なくスマホがロックされる。アラームはボタンでは止められず、ベッドを出ると自動で止まる。

#### 2. 二度寝を自動で検出して再アラーム
起床後 10 分以内にベッドへ戻ると、戻ってから 5 分後にアラームを自動で鳴らす。

#### 3. 起きるまでの速さをみんなで競える
アラームが鳴ってからベッドを出るまでの時間を記録し、他のユーザーとランキングで競える。自分の記録はまとめて削除できる。

### 解決出来ること
「アラームを止めて布団の中でスマホ → 二度寝」の流れを断ち切り、確実に布団から出る習慣を作れる。

### 今後の展望
* 睡眠中の圧力変化から寝返りや睡眠の質を可視化
* フレンド機能・チーム対抗戦
* iOS 対応

### 注力したこと（こだわり等）
* アラームの鳴動・起床判定・二度寝判定は M5Stack 側で完結させ、スマホ側からはアラームを解除できない（ロック中は API が拒否する）
* アラーム音量はハードのボタンで調整でき、初期値はミュートではなく最小音量
* Wi-Fi 不要。M5Stack とスマホは BLE でつながり、時刻もスマホから受け取る（アラーム処理と重ならないタイミングでのみ反映）

### セキュリティ（QR ペアリングと暗号化通信）
アラームを止めたいのは、寝ぼけた**本人**です。BLE に誰でも命令を送れると、ほかのスマホや nRF Connect から `cancel` を書き込むだけでアラームを止められ、ロックも外せてしまいます。そこで、**M5Stack の画面に表示した QR をカメラで読んだスマホだけ**が操作できるようにしました。

* M5Stack の右ボタンを長押しすると、LCD に QR を表示します（ベッドの外にいるときだけ）。アプリのホームにある「デバイスを追加」で読み取れば登録が終わります。一度登録すれば、以降は自動で暗号化して接続します
* QR には M5Stack の公開鍵（P-256）と使い捨てのトークンが入っています。スマホは ECDH で鍵を共有し、以降の通信はすべて AES-256-GCM で暗号化・改ざん検知します
* アプリにカメラの権限は不要です（Google Code Scanner を使用）。共有鍵は Android Keystore で暗号化して保存します

| 想定する脅威 | 対策 |
|---|---|
| 第三者が BLE でアラーム解除・設定変更を書き込む | 認証前のフレームと平文の命令はすべて捨て、LCD に `BLOCKED` 回数を表示する |
| 寝ぼけた本人が別のスマホを登録して止める | QR はベッドの外でしか表示できない。表示は120秒で、トークンは1回きり |
| 偽の M5Stack がロック解除の状態を送る | 公開鍵は QR から直接受け取る。GCM の検証に失敗した状態は信用しない |
| 睡眠・在床データの盗聴 | AES-256-GCM で暗号化する。認証前はセンサー値を送らない |
| 通信を録って再送する（リプレイ・反射） | 接続ごとの nonce、方向ごとに増えるカウンタ、IV に方向を入れる |
| QR の一部（公開鍵）をすり替える | devId = 公開鍵のハッシュなので、アプリが不一致を検出する |
| 会場にほかの M5Stack がある | 登録した `WakeUp-<devId>` にだけ接続する |

## 開発技術
### 活用した技術
#### API・データ
* M5Stack とスマホの BLE 通信（状態の notify とコマンドの write、どちらも JSON を AES-256-GCM で暗号化）
* QR ペアリング（M5GFX の QR 表示 / Google Code Scanner）、ECDH P-256・HKDF・AES-GCM（ESP32 の mbedtls / Java 標準の暗号 API）、Android Keystore
* Firebase Authentication（匿名認証）/ Cloud Firestore（記録・ランキング）

#### フレームワーク・ライブラリ・モジュール
* Arduino（M5Unified, ArduinoJson, ESP32 BLE）
* Android（Kotlin, Jetpack Compose）

#### デバイス
* M5Stack Basic
* FSR406（感圧センサ）
* Android スマートフォン

### 独自技術
#### ハッカソンで開発した独自機能・技術
* 圧力センサによる起床・二度寝判定のステートマシン（[arduino/wakeup_alarm/wakeup_alarm.ino](arduino/wakeup_alarm/wakeup_alarm.ino)）
* ベッドを出るまでスマホを使えなくするロック画面（[android/app/src/main/java/com/fk2603/wakeup/MonitorService.kt](android/app/src/main/java/com/fk2603/wakeup/MonitorService.kt)）
* QR で鍵交換する暗号化 BLE（[arduino/wakeup_alarm/secure_link.cpp](arduino/wakeup_alarm/secure_link.cpp) / [android/app/src/main/java/com/fk2603/wakeup/SecureChannel.kt](android/app/src/main/java/com/fk2603/wakeup/SecureChannel.kt)）

セットアップ手順は [arduino/README.md](arduino/README.md) と [android/README.md](android/README.md) を参照。
