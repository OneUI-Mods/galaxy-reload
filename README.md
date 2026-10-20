# Galaxy Reload

SystemUIの再読み込みをGUIとrootコマンドから実行するアプリです。画面ON・解除済みの状態を確認し、Adaptive Barと連携してその状態を引き継ぎます。認証情報やロック設定は変更しません。消灯・強制認証が起きたときは引き継ぎを中止します。

Galaxy S26 Ultra（SM-S948Q）のAndroid 16 / AZHLとAndroid 17 / BZI9向け。root、API 102対応Vector、SystemUIで有効なAdaptive Bar 0.6.18以降が必要です。Android 16は別に照合した契約を使い、現在の実機検証は17で行います。初回読み込みや失敗時は手動で解除する場合があります。

「コマンド」タブでコマンドを準備するか、rootでAPKのReloadCommandを実行してinstall-cliを指定します。

```sh
adb shell su -c '/data/local/tmp/galaxy-reload check'
adb shell su -c '/data/local/tmp/galaxy-reload reload'
```

checkは状態確認だけを行います。reloadはSystemUIだけを再読み込みし、端末本体・system_server・ホームを再起動しません。wrapperは更新後のAPKを自動で参照します。成功は終了コード0、拒否・失敗は2を返します。READY/SETUP/RELOADED/LOCKED/SCREEN_OFFなどの状態を標準出力へ返します。root権限は同じ署名の上書き更新で引き継げます。

Material 3、日本語・英語、タブ分割、検索可能なライセンス一覧を備えています。SDK 37 / minSdk 36、JDK 21、AGP 9.3.2でビルドします。配布は[releasing.md](docs/releasing.md)とtools/package_release.pyを使い、release variantと署名を検証します。
