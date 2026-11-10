# Syndesia

*σύνδεση: Greek for "connection".* Install apps on your Wear OS watch and move files to and from it, straight from your Android phone. No computer needed.

Syndesia talks to the watch over **Wireless debugging** (ADB over Wi-Fi), the same protocol the desktop `adb` tool uses. It pairs with the six-digit code (TLS + SPAKE2, via [libadb-android](https://github.com/MuntashirAkon/libadb-android)), finds watches on your network with mDNS, and remembers them.

## Features
- **Install**: single APKs, split APKs (pick them together) and `.apks` / `.xapk` bundles. Shows the app's name, icon and version, warns about phone-only apps, and explains install errors in plain English. Optional: allow downgrades, grant all permissions.
- **Share to install**: open or share an APK from Files, Chrome, Telegram… and choose Syndesia. Share any other file to drop it in the watch's Download folder.
- **Files**: browse the watch's storage, send files to it, save files to `Downloads/Syndesia` on the phone, rename, delete, make folders, install an APK that's already on the watch.
- **Watch apps**: open, force stop, save the APK, clear data, disable/enable, uninstall.
- **Dashboard**: battery, storage, memory, uptime, screenshots (save or share), and a command box for `adb shell` commands.
- Same Material You look as Bataria and Katharia: wallpaper colours, accent presets, animated backgrounds, pure black, welcome tour, in-app changelog.

## Install
Grab `Syndesia.apk` from the latest [release](../../releases). Every build is signed with the same key (`keystore/`), so updates install over the previous version and keep your paired watches.

## Watch setup (once)
1. **Settings › System › About › Versions** (or Software info): tap **Build number** 7 times to unlock Developer options.
2. Put the watch on the **same Wi-Fi** as the phone (**Settings › Connectivity › Wi-Fi**). Many watches turn Wi-Fi off while Bluetooth is connected; switching Bluetooth off on the watch for a moment forces Wi-Fi on.
3. **Settings › Developer options**: turn on **ADB debugging**, then **Wireless debugging**.
4. In Wireless debugging tap **Pair new device**. In Syndesia, the watch shows up under *On this Wi-Fi*; tap **Pair** and enter the code.

Older Wear OS 2 watches use **Debug over Wi-Fi** instead: choose *Enter address* and use port `5555`, then tap Allow on the watch.

## Build
GitHub Actions builds a signed release APK on every push (`.github/workflows/build.yml`) and publishes it as a release.
