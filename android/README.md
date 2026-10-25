# Android アプリ

> **注意**: パスに日本語などの非 ASCII 文字が含まれていると、Android のビルドは失敗します。リポジトリは ASCII 文字だけのパスに clone してください。

## セットアップ

1. Android Studio で `android/` を開き、ビルドして実機にインストールする
2. 初回起動時に「付近のデバイス」（Bluetooth）と通知を許可し、ホーム画面の案内から「他のアプリの上に重ねて表示」「電池の最適化の無効化」「使用状況へのアクセス」も許可する
3. Bluetooth をオンにすると M5Stack に自動で接続する。デバイス画面でニックネームを保存し、ホーム画面の ＋ からアラームをセットする

## 設定値

`app/src/main/java/com/fk2603/wakeup/Config.kt` の上部で変更する。

| 定数 | 初期値 | 内容 |
|---|---|---|
| `BLE_SCAN_TIMEOUT_MS` | 10000 | M5Stack を探すスキャンの長さ |
| `BLE_RECONNECT_DELAY_MS` | 3000 | 接続が切れてから再接続までの待ち時間 |
| `BLE_STALE_MS` | 10000 | この時間状態が届かなければ接続をやり直す |
| `TIME_SYNC_INTERVAL_MS` | 240000 | スマホの時刻を M5Stack に送る間隔（4分） |
| `UNLOCK_IF_UNREACHABLE_SEC` | 300 | ロック中に M5Stack と通信できない状態がこの時間続いたらロックを解除 |
| `EMERGENCY_UNLOCK_SEC` | 300 | 緊急解除でロックを外す時間（5分） |
| `EMERGENCY_CONFIRM_MS` | 5000 | 緊急解除ボタンの2回目をこの時間内に押すと解除 |
| `DAY_START_HOUR` | 4 | 連続記録の1日の区切り（朝4時） |
| `UNLOCK_AFTER_LEAVE_BED_SEC` | 3 | ベッドを出てこの秒数たったらロックを外す（M5Stack の起床確認を待たない） |

## ビルド

- Compose は debug ビルドだと動作が重い。Android Studio の **Build Variants** で `release` を選ぶと、R8 で最適化された版を ▶ で入れられる（署名は debug 鍵）

## しくみ

- `MonitorService`（フォアグラウンドサービス）が `M5Link` で M5Stack と BLE 接続を保ち、1秒ごとに届く状態を受け取る
- 接続時と4分ごとにスマホの時刻を M5Stack に送る
- アラーム中（RINGING / RESLEEP_ARMED）は全画面オーバーレイ（`LockOverlay`、Compose で描画）でスマホ全体をロックする
- ベッドにいる間は、前面のアプリがデバイス画面で ON にした種類（ショート動画 / SNS / 動画 / ゲーム）のときだけロックする（`AppBlocker`。アプリのカテゴリと主要アプリのパッケージ名で判定）。「使用状況へのアクセス」が未許可なら従来どおり全体をロックする
- 画面ロックを解除した瞬間（`ACTION_USER_PRESENT`）にもロックを判定する。M5Stack と切れたままアラーム時刻を過ぎたら、鳴っているとみなしてロックする
- 初回は「電池の最適化を無効にする」を許可する（OPPO などで画面オフ中に監視が止まらないように）
- 新しい起床記録を検出すると、端末の `wake_records.csv` に保存する。他のユーザーとの共有（ランキングなど）はしない
- 睡眠記録は CSV ファイル `Android/data/com.fk2603.wakeup/files/sleep_records.csv` に保存する（`SleepStore`）。PC から USB や `adb pull /sdcard/Android/data/com.fk2603.wakeup/files/sleep_records.csv` で取り出せる。列は `就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻`（起床が空欄 = 記録中、`不明` = 起床が届かなかった、戻った時刻は `;` 区切り）。ファイルを書き換えて戻すと、戦績画面の「更新」で読み直す。M5Stack から届く就寝（S）・二度寝（R）・起床（E）のイベントで組み立て、受け取るたびに `evack` を返す
- 端末を再起動しても `BootReceiver` が監視を再開する

## 画面（UI）

- UI は AppUI ブランチの `ドパドパクラッシャー/`（Next.js プロトタイプ）を Compose に移植したもの（`ui/dopa/`）。色・フォントは `ui/theme/DopaTheme.kt`、見出しフォントは Dela Gothic One（`res/font`、OFL）
- 画面は `ui/dopa/UiModels.kt` の UI 状態だけを受け取る。バックとの接続は `ui/AppViewModel.kt` と `ui/dopa/AppShell.kt`（アプリ内）、`MonitorService` → `LockOverlay`（ロック画面）に集約しているので、UI を差し替えるときはここを直す
- ホーム・戦績・クリア・アラーム・ロック画面はスクロールせず1画面に収める（デバイス画面とスマホ使用データ画面はスクロールする）
- XP・レベル・連続起床・バッジ・7日間グラフは端末の起床記録（`WakeStore`）から毎回計算する（`Gamification.kt`。保存はしない）
- 圧力ログは直近8時間を1分平均で `filesDir/pressure_log.csv` に保存する（`PressureLog`）
- デバイス画面の「在床しきい値」「離床確認時間」は M5Stack に `config` コマンドで送り、M5Stack 側に保存される（ベッドにいる間・アラーム中は変更不可）
- ホームの「アラームを鳴らす」はアプリ内だけのデモで、記録や XP には影響しない

## 端末の記録（すべて CSV）

場所: `Android/data/com.fk2603.wakeup/files/`（PC から USB や `adb pull` で取り出せる）。UTF-8（BOM 付き）・1行目は見出し・Excel でそのまま開ける。読み書きは `CsvFile.kt` にまとめている。設定値（ニックネーム・封印カテゴリ・取り込み済みフラグなど）は SharedPreferences（`Prefs`）に持つ。

| ファイル | 列 | 書く処理 |
|---|---|---|
| `wake_records.csv` | `id,鳴動,起床,離床秒,二度寝,source,synced` | `WakeStore`（M5Stack・デモ） |
| `sleep_records.csv` | `就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻` | `SleepStore`（S/R/E イベント） |
| `pressure_log.csv` | `時刻,raw`（1分平均・直近8時間） | `PressureLog` |
| `emergency_log.csv` | `時刻`（緊急解除1回につき1行） | `EmergencyUnlock` |
| `routine_log.csv` | `日付,タスクID,タスク,完了`（その日のルーティン1つにつき1行） | `RoutineStore` |
| `screen_time_daily.csv` | `日付,利用時間(ms)` | `ScreenTimeStore` |
| `screen_time_baseline.csv` | `導入日時,導入前の1日平均(ms),期間開始,期間終了,日数,元データ` | `ScreenTimeStore` |

- 前の形式（緊急解除の回数・スマホ使用データを SharedPreferences に持っていた版）から更新したときは、初回に CSV へ移す

### 1週間分のデモ

- M5Stack から届いた起床記録は必ず `wake_records.csv` に保存する（`source=m5`）
- **デモ**: `app/src/main/assets/demo/` の `wake_records.csv`（起床8件）と `sleep_events.csv`（睡眠イベント15件）を、初回起動時に1回だけ `DemoSeed` が取り込む。M5Stack から届いたときと同じ `WakeStore.add()` / `SleepStore.apply()` を通すので、戦績の計算・グラフはすべて本番と同じ仕組みで動く。日付は「何日前」で書いてあり、取り込んだ日を基準に直近1週間に並ぶ。デモの起床記録は `source=demo`
- 取り込み済みかは `Prefs.demoImported`。もう一度取り込むにはアプリのデータを消す
- 以後の実際の記録は同じファイルに追記されていく

## 緊急解除と連続記録（feature/screen-time-compare から移植）

- ロック画面・アラーム画面の「緊急解除」（動作確認用の仮ボタン。デザインはチームで差し替える）を2回押すと、`EMERGENCY_UNLOCK_SEC`（5分）だけロックを外す。アラームは止まらない（M5Stack 側で鳴り続ける）
- 1回押すと「〇日の連続記録が0に戻ります」と表示して確認する
- 緊急解除の回数と「緊急解除しなかった日」の連続日数を記録する。1日の区切りは朝4時。今日はまだ終わっていないので、昨日までの日数を数える
- 解除した時刻を端末の `emergency_log.csv` に1行ずつ保存し、回数・最終解除日・最高連続はそこから計算する
- UI から使う値: `AppViewModel.emergency`（回数・連続・最高記録）、`AppViewModel.emergencyUnlock()`

| ファイル | 内容 |
|---|---|
| `StreakMath.kt` | 連続日数の計算（Android に依存しない） |
| `EmergencyUnlock.kt` | 緊急解除の実行と状態 |

## スマホ使用データ（導入前後の比較）

- スマホ全体の1日の合計利用時間（`UsageStatsManager.queryUsageStats` の `totalTimeInForeground`。ホームアプリ・システム UI・このアプリは除く）を、導入前と導入後で比べるためのバック
- Android が日ごとの記録を残すのは数日〜1週間ほどなので、`MonitorService` が1時間ごとに日ごとの合計を `screen_time_daily.csv` に写して貯める（`ScreenTimeStore`）
- 導入前の1か月平均は、長く残る週ごとの記録（なければ月ごと、日ごと）から作り、導入時に `screen_time_baseline.csv` に保存する
- **戦績画面の「スマホ使用データ」ボタン**で、この端末で読めるか確認できる（簡易画面。アクセス許可の有無、Android に残っている量、日ごとの合計、導入前の平均、緊急解除の回数）
- UI から使う値: `AppViewModel.screenTime` と `loadScreenTime()`。計算は `ScreenTimeMath`（曜日別・導入前後の差など）

| ファイル | 内容 |
|---|---|
| `ScreenTimeMath.kt` | 基準・曜日別などの計算（Android に依存しない） |
| `ScreenTime.kt` | 利用記録の読み取り・端末内への保存 |
| `ui/dopa/ScreenTimeCheckScreen.kt` | 読めるかの確認画面（簡易） |

## ホーム: アラームと朝のルーティン

- **アラーム（複数・曜日つき）**: ホームの ALARMS の ＋ で追加（曜日を選ばなければ1回だけ）。オン/オフ・削除ができる。設定値なので SharedPreferences `dopa_local_alarms` に保存（`AlarmSchedule`）
  - M5Stack は1件しか持てないので、`MonitorService` が**有効なアラームのうち一番近い時刻を自動で M5Stack に送る**（M5Stack が IDLE / ARMED / AWAKE のときだけ。送れなければ10秒おきに再試行）。鳴り終わると次の曜日の分を送り直し、全部オフなら解除する
  - 1回だけのアラームは鳴る時刻を覚えておき、過ぎたら自動でオフになる
- **MORNING ROUTINE**: 「編集 ＋」でタスクを登録（毎週の曜日か、指定日だけ）。定義は SharedPreferences `dopa_routines`、毎日のチェック結果は `routine_log.csv`（`RoutineStore`）
  - ホームに出すのは「次のアラームの日」のルーティン（次のアラームが今日なら今日。18時以降で次が明日以降ならその日。それ以外は今日）
  - その日を最初に表示したときに、当てはまるタスクを未完了で記録に書き出す（チェックしなかった日も記録に残る）
  - 戦績の「朝タスクの記録」に、日ごとの 完了数 / タスク数 と達成度（0〜10 に換算）、直近7日の平均を出す
  - 起きたあとのクリア画面に、その日のルーティンを「今日の予定｜これを目標に動き出そう！」として出す
  - デモ: `assets/demo/routine_tasks.csv`（4件）と `routine_log.csv`（1週間分のチェック）を初回だけ `RoutineStore` で取り込む（`Prefs.demoRoutinesImported`）

## ロックが外れるタイミングと再接続モード

- ベッドを出て `UNLOCK_AFTER_LEAVE_BED_SEC`（3秒）たったら、M5Stack がまだ起床確認中でもアプリのロックを外す。ベッドに戻ればすぐロックする（M5Stack の判定・記録はそのまま）
- M5Stack の右ボタンを長押しして再接続モード（QR 表示）にすると、状態の `p:1`（またはつなぎ直しの hello）を見て、スマホ側も自動で登録を解除する。ホームに「デバイスを追加」が出るので、表示中の QR を読み直す（このスマホが QR を読んでいる最中は解除しない）

