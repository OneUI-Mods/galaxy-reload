# 開発記録

SDK37 / min36。GUIとCLIはReloadCommandを共用する。root UID以外は拒否。固定SystemUIパッケージ/PIDを検証してTERMし、root helperは10秒で完了確認またはキャンセルする。controller停止・ホーム・system_server・OS再起動は行わない。

protocol1の同一ReloadTicketを両リポジトリへ配置し、4件のJVM試験で期限境界・未来時刻・boot/user切替・画面OFF・CE未unlock・不正入力を確認する。配布署名と最低バージョンを照合し、helperは画面ON・device/keyguard unlockedを停止前に2回確認する。native側の詳細はAdaptive Barのdocs/compatibility/reload-session.md。

CLI wrapperは自分の識別コメントがある既存ファイルのみ更新する。別ファイルとsymlinkは上書きしない。相互実行はhost-private FileLockで直列化する。要求はprivate ownerはSystemUIの実UID/mode0600、原子的rename、有効12秒、単回消費。実機結果は下記に記録する。

要求ファイルに加えて、生存するroot helperとのLocalSocket handshakeを必須にする。双方のkernel peer UIDを照合し、helperは新SystemUIのPID/cmdlineも確認する。helper停止後のstale markerだけでは引き継げない。


## Android 17 / BZI9実機（2026-10-21）

署名済み0.2.0を準備版0.1.0へ上書きし、同じアプリUIDと許可済みSUを維持した。CLI reloadとGUIの「SystemUIを再読み込み」の両方が成功。画面ON・解除済みを維持し、SystemUIのPIDだけが変化、system_server・One UIホーム・kernel boot IDは不変だった。初期化の完了条件はnativeのmShowing=falseに加え、currentKeyguardState=GONEも必要。初期startup遷移の後着によるステータスバー非表示を修正し、時計と右側の表示・Adaptive Barのキャプチャ再開まで確認した。

GUIのplain suはアプリのデータ隔離mountを引き継ぎ、他アプリの保存先がENOENTになった。KernelSU su --helpで-Mを確認してglobal mount namespaceを使用する。SU確認と実処理は同じ設定を使用する。boot countの通常Settings providerとKeyguardManagerの初期化はroot app_processでは失敗したため、固定settings CLIと直接Binderに切り替えた。SystemUIの実UIDはPackageManagerと/procで照合し、1000に固定しない。

初期未完了試験のロック状態ではCLI checkがLOCKEDを返した。最終版で意図的な消灯・ロックは利用者の指示により実施しない。期限・boot/user違い・画面OFF条件は4件のJVM試験で確認する。Android 16の契約はAPKを別に照合し、Android 17からの推定fallbackは使わない。16実機再試験は未実施。

コマンドタブの「コマンドを準備」から固定wrapperを更新し、GUIの完了表示を確認。非root CLIは終了コード2 / ROOT_REQUIREDとなりSystemUI PIDは変化しなかった。3本の配布APKと端末base.apkのSHA-256は一致。現在のcrash guardはblocked=false、crashesは空。検証用privacy fixtureは削除し、消灯せずホームへ戻した。
