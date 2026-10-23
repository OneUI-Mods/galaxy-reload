# Galaxy Reload

画面ON・解除済みの状態から、SystemUIを再読み込みするroot向けアプリです。Galaxy Adaptive Barと連携して解除状態を引き継ぎ、認証情報やロック設定を変更せずに再読み込みします。

## 対応環境

Galaxy S26 Ultra（SM-S948Q）のAndroid 16 / AZHLとAndroid 17 / BZI9向けです。root、API 102対応Vector、SystemUIで有効なGalaxy Adaptive Bar 0.6.18以降が必要です。初回読み込みや引き継ぎに失敗した場合は、手動のロック解除が必要なことがあります。

## 使い方

1. [Releases](https://github.com/OneUI-Mods/galaxy-reload/releases)から署名済みrelease APKをインストールします。
2. アプリを開き、root管理アプリで権限を許可します。
3. 端末が点灯・解除済みであることを確認し、状態確認後に再読み込みします。

ロック中、消灯中、強制認証が必要な状態では引き継ぎません。操作中にその状態へ変わった場合も中止します。再読み込みの対象はSystemUIです。

## コマンドから使う

アプリの「コマンド」タブでコマンドを準備すると、ADBから次を実行できます。

```sh
adb shell su -c '/data/local/tmp/galaxy-reload check'
adb shell su -c '/data/local/tmp/galaxy-reload reload'
```

`check`は状態確認、`reload`は再読み込みです。コマンドはインストール済みAPKを参照するため、同じ署名のアプリ更新後も利用できます。画面に拒否理由が表示された場合は、その状態を解消してから再実行してください。

## 開発者向け・ライセンス

ビルド、内部構造、検証方法は[開発者向けガイド](docs/development.md)に分けています。不具合報告は[Issues](https://github.com/OneUI-Mods/galaxy-reload/issues)へ、端末・OS・対象アプリの版と再現手順を添えてください。

本体のライセンスは[LICENSE](LICENSE)、依存ライブラリの表記はアプリ内または同梱の第三者ライセンスを参照してください。Samsungの公式アプリではありません。

[OneUI Modsのアプリ一覧](https://github.com/OneUI-Mods)
