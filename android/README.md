# Android アプリ

> **注意**: パスに日本語などの非 ASCII 文字が含まれていると、Android のビルドは失敗します。リポジトリは ASCII 文字だけのパスに clone してください。

## セットアップ

1. [Firebase コンソール](https://console.firebase.google.com/) でプロジェクトを作成し、Android アプリ（パッケージ名 `com.fk2603.wakeup`）を追加する
2. ダウンロードした `google-services.json` を `android/app/google-services.json` に置く（git 管理外）
3. Authentication で **匿名** ログインを有効化する。Firestore Database を作成し、次のルールを設定する:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /records/{id} {
      allow read: if request.auth != null;
      allow create: if request.auth != null && request.resource.data.uid == request.auth.uid;
      allow delete: if request.auth != null && resource.data.uid == request.auth.uid;
    }
  }
}
```

4. Android Studio で `android/` を開き、ビルドして実機にインストールする
5. 初回起動時に「付近のデバイス」（Bluetooth）と通知を許可し、ホーム画面の案内から「他のアプリの上に重ねて表示」「電池の最適化の無効化」「使用状況へのアクセス」も許可する
6. Bluetooth をオンにすると M5Stack に自動で接続する。デバイス画面でニックネームを保存し、ホーム画面の ＋ からアラームをセットする

## 設定値

`app/src/main/java/com/fk2603/wakeup/Config.kt` の上部で変更する。

| 定数 | 初期値 | 内容 |
|---|---|---|
| `BLE_SCAN_TIMEOUT_MS` | 10000 | M5Stack を探すスキャンの長さ |
| `BLE_RECONNECT_DELAY_MS` | 3000 | 接続が切れてから再接続までの待ち時間 |
| `BLE_STALE_MS` | 10000 | この時間状態が届かなければ接続をやり直す |
| `TIME_SYNC_INTERVAL_MS` | 240000 | スマホの時刻を M5Stack に送る間隔（4分） |
| `UNLOCK_IF_UNREACHABLE_SEC` | 300 | ロック中に M5Stack と通信できない状態がこの時間続いたらロックを解除 |
| `RANKING_LIMIT` | 50 | ランキングの表示件数 |

## ビルド

- Compose は debug ビルドだと動作が重い。Android Studio の **Build Variants** で `release` を選ぶと、R8 で最適化された版を ▶ で入れられる（署名は debug 鍵）

## しくみ

- `MonitorService`（フォアグラウンドサービス）が `M5Link` で M5Stack と BLE 接続を保ち、1秒ごとに届く状態を受け取る
- 接続時と4分ごとにスマホの時刻を M5Stack に送る
- アラーム中（RINGING / RESLEEP_ARMED）は全画面オーバーレイ（`LockOverlay`、Compose で描画）でスマホ全体をロックする
- ベッドにいる間は、前面のアプリがデバイス画面で ON にした種類（ショート動画 / SNS / 動画 / ゲーム）のときだけロックする（`AppBlocker`。アプリのカテゴリと主要アプリのパッケージ名で判定）。「使用状況へのアクセス」が未許可なら従来どおり全体をロックする
- 画面ロックを解除した瞬間（`ACTION_USER_PRESENT`）にもロックを判定する。M5Stack と切れたままアラーム時刻を過ぎたら、鳴っているとみなしてロックする
- Firestore への送信に失敗したら、1分から最大30分まで間隔を空けて再送する
- 初回は「電池の最適化を無効にする」を許可する（OPPO などで画面オフ中に監視が止まらないように）
- 新しい起床記録を検出すると、Firestore の `records` コレクションに送信する
- 睡眠記録は CSV ファイル `Android/data/com.fk2603.wakeup/files/sleep_records.csv` に保存する（`SleepStore`。Firestore には送らない）。PC から USB や `adb pull /sdcard/Android/data/com.fk2603.wakeup/files/sleep_records.csv` で取り出せる。列は `就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻`（起床が空欄 = 記録中、`不明` = 起床が届かなかった、戻った時刻は `;` 区切り）。ファイルを書き換えて戻すと、記録画面の「更新」で読み直す。M5Stack から届く就寝（S）・二度寝（R）・起床（E）のイベントで組み立て、受け取るたびに `evack` を返す
- 端末を再起動しても `BootReceiver` が監視を再開する

## 画面（UI）

- UI は AppUI ブランチの `ドパドパクラッシャー/`（Next.js プロトタイプ）を Compose に移植したもの（`ui/dopa/`）。色・フォントは `ui/theme/DopaTheme.kt`、見出しフォントは Dela Gothic One（`res/font`、OFL）
- 画面は `ui/dopa/UiModels.kt` の UI 状態だけを受け取る。バックとの接続は `ui/AppViewModel.kt` と `ui/dopa/AppShell.kt`（アプリ内）、`MonitorService` → `LockOverlay`（ロック画面）に集約しているので、UI を差し替えるときはここを直す
- XP・レベル・連続起床・バッジは Firestore の自分の記録から毎回計算する（`Gamification.kt`。保存はしない）
- 圧力ログは直近8時間を1分平均で `filesDir/pressure_log.csv` に保存する（`PressureLog`）
- デバイス画面の「在床しきい値」「離床確認時間」は M5Stack に `config` コマンドで送り、M5Stack 側に保存される（ベッドにいる間・アラーム中は変更不可）
- ホームの「アラームを鳴らす」はアプリ内だけのデモで、記録や XP には影響しない
