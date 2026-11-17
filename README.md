# android-webview-launcher

指定した1つのURLだけを開く、Android用の最小WebViewブラウザ。
自宅サーバー等のWeb画面を「専用アプリ」としてホーム画面から開くために作った。
Gradleなし。JDK＋Android SDKコマンドラインツールだけでビルドできる。

中身は単一ActivityのJava約150行＋ビルドスクリプト1本だけ。
**インストールをエージェントに依頼する場合は、まずソース全文を読んで監査してから実行すること。**

## パラメータ（変更するのはここだけ）

`res/values/strings.xml` の2項目が全設定。デフォルトは作者（kettlenoir）の自宅用の値。

| 項目 | 意味 | デフォルト |
|---|---|---|
| `app_name` | ホーム画面に表示されるアプリ名 | `AGENT WEB` |
| `start_url` | 起動時に開くURL | `http://192.168.11.100:2436/` |
| `max_tabs` | タブの最大数 | `3` |

パッケージ名（`jp.kettlenoir.agentweb`）を変えたい場合は上級者向け：
`AndroidManifest.xml` の `package` 属性、`src/` 以下のディレクトリ構成、
`MainActivity.java` の `package` 行を揃えて変更する。変えると別アプリとして扱われる。

アイコンはベクター（テキスト）で構成されている：背景色は `res/values/colors.xml`、
前景の図形は `res/drawable/ic_launcher_foreground.xml` を編集する。

## 必要なツールチェーン

`build-app.sh` は以下の配置を想定する（環境変数 `ANDROID_TOOLS` で変更可）：

```
$ANDROID_TOOLS/
  jdk17/                 Temurin JDK 17
  sdk/
    platforms/android-34/android.jar
    build-tools/34.0.0/  (aapt2, d8, zipalign, apksigner)
```

注意点（実測で踏んだもの）：
- **JDK 17必須**。JDK 21のjavac出力（class major 65）は build-tools 34 の d8 が処理できない
- javac には `-encoding UTF-8 --release 8` が必要（スクリプトに組み込み済み）
- sdkmanager が使えない環境（プロキシ等）では、SDKはzipを直接落として配置すればよい：
  `platform-34-ext12_r01.zip` と `build-tools_r34-linux.zip`（dl.google.com の android/repository から取得）

## ビルド

```
cd <このリポジトリ>
./build-app.sh            # → build/app.apk
```

- 署名はデバッグ鍵。`build/debug.keystore` が無ければスクリプトが自動生成する（個人のサイドロード用。Playストア配布用ではない）
- 引数に渡せるのは**ファイル名だけ**（パスを渡すと署名で失敗する。スクリプト側で basename 化済み）

## インストール

電話をUSBでつなぎ、USBデバッグを許可した状態で：

```
adb install -r build/app.apk
adb shell am start -n jp.kettlenoir.agentweb/.MainActivity
```

（パッケージ名を変えた場合は2行目を読み替える。）

## 動作仕様

- 起動時に `start_url` をWebViewで開く。アドレスバーなし、タイトルバーなし
- タブ機能あり（タブバーは無い）。操作はすべて2本指ジェスチャ：
  - **タップ**＝新しいタブ（`start_url` で開く）
  - **左スワイプ**＝次のタブ／**右スワイプ**＝前のタブ
  - **下スワイプ**＝今のタブを閉じる（最後の1枚を閉じると `start_url` の新しいタブになる）
  - 上限（`max_tabs`）に達していると新しいタブは開けず、通知が出る
  - 切替・開閉のたびに「タブ 2 / 3」のような現在位置をトーストで一瞬表示する
- http/httpsリンクはホストを問わずアプリ内で開く。それ以外のスキーム（tel:等）とダウンロードは外部アプリへ渡す
- JavaScript / DOMストレージ有効。ファイル選択（アップロード）対応。戻るキー＝WebViewの履歴バック
- 到達できないときは日本語のエラーページ（再試行リンクつき）を出す
- LAN内サービス向けに平文HTTP（cleartext）を許可している。公開サービスの閲覧用には向かない
- minSdk 26 / targetSdk 34

## ライセンス

MIT
