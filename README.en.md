# Galaxy Reload

Installation requires Android 17 (API 37) or later.

[日本語](README.md) · English

A root companion for reloading SystemUI while the screen is on and the phone is already unlocked. It works with Galaxy Adaptive Bar to carry the unlocked state across the reload without changing credentials or lock settings.

## Requirements

Targets Galaxy S26 Ultra (SM-S948Q), Android 17 / BZI9. Requires root, Vector with API 102 support, and Galaxy Adaptive Bar 0.6.18+ enabled in SystemUI. Initial loading or a failed handoff may still require manual unlocking.

## Usage

1. Install the signed release APK from [Releases](https://github.com/OneUI-Mods/galaxy-reload/releases).
2. Open the app and grant permission in your root manager.
3. Confirm that the display is on and unlocked, check status, then reload.

Unlock-state handoff is refused while locked, screen-off, or requiring strong authentication. If the state changes during the operation, it is canceled. Only SystemUI is reloaded.

## Command-line use

Prepare commands in the app's **Commands** tab, then run from ADB:

```sh
adb shell su -c '/data/local/tmp/galaxy-reload check'
adb shell su -c '/data/local/tmp/galaxy-reload reload'
```

To reload only SystemUI while preserving the lock screen, explicitly run as root:

```sh
adb shell su -c '/data/local/tmp/galaxy-reload reload-locked'
```

`reload-locked` creates no unlock request and keeps the lock screen. It requires the screen to be on, the Android owner user, and completion of the first authentication since boot. This is separate from normal `reload` with unlock-state handoff.

`check` checks state; `reload` performs the reload. The command references the installed APK, so it remains usable after same-signature app updates. If a refusal reason is shown, resolve that condition before retrying.

## Development and licenses

Build instructions, architecture, and verification are in the [developer guide (Japanese)](docs/development.md). Report problems through [Issues](https://github.com/OneUI-Mods/galaxy-reload/issues), including device, OS and target-app versions, and reproduction steps.

See [LICENSE](LICENSE) for the app's license and the in-app or bundled third-party notices for dependencies. This is not an official Samsung app.

[OneUI Mods app catalog](https://github.com/OneUI-Mods)

## String compatibility

The previous handling of certain whitespace in license search and version display has been restored.

[Build environment / ビルド環境](docs/build-environment.md)
