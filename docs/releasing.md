# 配布と署名

JDK 21とSDK 37で `python3 tools/package_release.py --source-ref <commit>` を実行します。release variant、targetSdk 37、非debuggable、検証Activity非混入、ライセンス実依存、16KiB alignment、署名を検査し、APK・ソースZIP・SHA-256を生成します。

配布鍵はGit管理外の `.signing/release.keystore`、aliasは `galaxyreload-release`、パスワードは `.signing/password` に保存します。ディレクトリは0700、ファイルは0600。鍵とパスワードをGit、ログ、配布ZIPへ含めません。以降の上書き更新にも同じ鍵を使います。

GitHub Releaseへは `dist/GalaxyReload-*-release.apk` の検証済み署名版だけを添付してください。CIの未署名成果物は開発用で、そのまま配布しません。リリース文は外部利用者向けに、機能・対応環境・更新手順・制限を書きます。
