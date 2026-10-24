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
5. 初回起動時に「付近のデバイス」（Bluetooth）と通知を許可し、「他のアプリの上に重ねて表示」も許可する
6. Bluetooth をオンにすると M5Stack に自動で接続する。ニックネームを入力して保存し、アラームをセットする

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
- `locked` の間は全画面オーバーレイ（`LockOverlay`）を表示し、他のアプリを操作できないようにする
- 画面ロックを解除した瞬間（`ACTION_USER_PRESENT`）にもロックを判定する。M5Stack と切れたままアラーム時刻を過ぎたら、鳴っているとみなしてロックする
- Firestore への送信に失敗したら、1分から最大30分まで間隔を空けて再送する
- 初回は「電池の最適化を無効にする」を許可する（OPPO などで画面オフ中に監視が止まらないように）
- 新しい起床記録を検出すると、Firestore の `records` コレクションに送信する
- 睡眠記録は CSV ファイル `Android/data/com.fk2603.wakeup/files/sleep_records.csv` に保存する（`SleepStore`。Firestore には送らない）。PC から USB や `adb pull /sdcard/Android/data/com.fk2603.wakeup/files/sleep_records.csv` で取り出せる。列は `就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻`（起床が空欄 = 記録中、`不明` = 起床が届かなかった、戻った時刻は `;` 区切り）。ファイルを書き換えて戻すと、記録画面の「更新」で読み直す。M5Stack から届く就寝（S）・二度寝（R）・起床（E）のイベントで組み立て、受け取るたびに `evack` を返す
- 端末を再起動しても `BootReceiver` が監視を再開する
