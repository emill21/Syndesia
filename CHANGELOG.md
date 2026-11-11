# Changelog

## 1.4 — 2026-11-11 · Bluetooth transfers

- Install apps over Bluetooth: when the watch isn't on Wi-Fi, apps go to Syndesia on the watch, which asks you to confirm each install
- Send files over Bluetooth from the Files tab or the share sheet. Music lands in Music, pictures in Pictures, videos in Movies, everything else in Download, each in a Syndesia folder
- Update the watch helper from the Install tab (over Wi-Fi) to turn this on

## 1.3 — 2026-11-11 · Watch helper

- New: Syndesia for your watch. Install it from the Install tab with one tap; it's bundled inside the phone app
- When Syndesia can't reach the watch, it asks the watch over Bluetooth to turn on Wi-Fi and Wireless debugging, then connects by itself
- Keeps the watch's Wi-Fi up while you're connected, and lets it go again when you disconnect
- Wake watch Wi-Fi button on the Watch tab when it isn't connected

## 1.2 — 2026-11-11 · Disconnect fixed

- Disconnect works again, in the app and on the notification, even if the watch has stopped answering
- After you disconnect, Syndesia no longer reconnects by itself until you connect again (or reopen the app)
- If the watch goes silent, Syndesia notices and frees up the connection instead of getting stuck

## 1.1 — 2026-11-10 · Stays connected

- The watch now stays connected until you tap Disconnect or swipe Syndesia away from Recents, even while you're choosing files or using other apps
- A quiet "Connected to your watch" notification keeps the link alive in the background, with a Disconnect button
- Keep-alive pings stop the watch's Wi-Fi from dozing off mid-session
- If the link drops for a moment, Syndesia reconnects by itself (and follows the watch to a new port)

## 1.0 — 2026-11-10 · Hello, Syndesia

- Find watches on your Wi-Fi automatically, pair with the six-digit code, and reconnect on its own next time
- Install APKs, split APKs and .apks / .xapk bundles, with progress and plain-English errors
- Open or share an APK from any app to send it straight to your watch
- Browse the watch's storage: upload, download, rename, delete and make folders
- See every app on the watch: open, force stop, save its APK, clear data, disable or uninstall
- Watch dashboard: battery, storage, memory, screenshots and a command box
- Material You look with wallpaper colours, accents, animated backgrounds and a welcome tour
