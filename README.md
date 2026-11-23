# Explorer for OPPO Watch X2

A file explorer for the watch's own storage, built for the **Chinese OPPO Watch X2 (ColorOS Watch, plain Android 11)**. It needs no Google Play services, no Wear OS and no Google libraries. CI checks the APK for anything Google- or Wear-only on every build.

On the watch you can:

- browse the watch storage (and any other storage volume). **Downloads** (`/sdcard/Download`, where Home Files saves its downloads) is the first thing on the home screen, with shortcuts to Music, Pictures and other common folders below
- scroll smoothly with the crown: each click moves the list a fixed distance, whatever size the watch reports for it
- tap a file to open it: images (pinch or turn the crown to zoom, double-tap to reset), text files, web pages (.html, shown with the system WebView), music (crown changes the volume) and videos are shown in the app; other files go to any app that can open them
- long-press a file or folder for its options: copy, move, rename, delete, open with another app, and its size
- copy or move: pick Copy or Move, go to the folder you want, and tap **Paste here**. Name clashes get "(1)" added instead of overwriting.
- create folders, sort by name, date or size, and show or hide hidden files
- install an APK you've copied to the watch, if the watch allows it

## Install on the watch

1. Get `Explorer-release.apk` from the `apk-builds` branch (or from the `Explorer-apk` artifact of the latest green **Build APK** run).
2. With USB debugging on and the watch connected:
   ```
   adb install -r Explorer-release.apk
   adb shell appops set dev.hamza.explorer MANAGE_EXTERNAL_STORAGE allow
   ```
   The second line gives the app "All files access". ColorOS Watch may not have the settings screen for it, so adb is the reliable way. Without it the app can still read photos, music and videos if you allow read-only access.

## Signing

Every build is signed with one fixed sideload key, so `adb install -r` updates keep working. The key is never committed: CI reads it from the `SIGNING_KEYSTORE_B64` repository secret (the base64 of the `.jks` file). Without the secret, CI still builds and tests the app with a throwaway key, but doesn't publish it to `apk-builds`.

## Project layout

- `filecore/` plain Kotlin/JVM file operations (list, copy, move, rename, delete, text preview) with unit tests
- `app/` the watch app (Jetpack Compose for Wear OS, which is plain AndroidX and runs without Wear OS)
- `ci/emulator-smoke.sh` drives the release APK on an Android 11 emulator with no Google services: browse, view, copy, rename, move and delete
- `.github/workflows/build.yml` runs the tests, builds the APKs and runs the emulator check on every push

Run the core tests locally without the Android SDK:

```
./gradlew -PcoreOnly :filecore:test
```
