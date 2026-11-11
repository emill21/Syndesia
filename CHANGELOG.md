# Changelog

## 1.7 — 2026-11-11 · Phone hotspot

- Connect via phone hotspot: one tap starts a private hotspot on your phone, your watch joins it over Bluetooth, and Syndesia connects over it. Works anywhere, no router needed
- The hotspot turns itself off when you disconnect or close Syndesia
- Skip the network prompt: while connected, switch the watch to port 5555 until it restarts so it doesn't ask about each new network
- Update the watch app from the Install tab (over Wi-Fi) to get hotspot joining

## 1.6.1 — 2026-11-11 · Bluetooth fix

- Fixed: the phone couldn't find Syndesia on the watch over Bluetooth, so Bluetooth sending, installing and waking the watch's Wi-Fi never kicked in. Just updating the phone app makes it work with the watch app you already have
- The watch app now advertises itself properly too (update it from the Install tab next time you're on Wi-Fi)

## 1.6 — 2026-11-11 · A better watch app

- Watch app redesigned with Material 3 Expressive for Wear OS
- Send files from the watch to the phone over Bluetooth: tap Send to phone on the watch, pick a file, and it lands in Downloads/Syndesia/From watch
- Connect to phone, from the watch: it turns on its Wi-Fi and Wireless debugging and asks the phone to link up
- Screenshot from the watch: tap it, and 5 seconds later the phone saves a screenshot of whatever's on the watch
- Live transfer progress on the watch, both directions
- Update the watch helper from the Install tab to get all of this

## 1.5 — 2026-11-11 · Record, preview, save folders

- Install queue memory: your queue survives closing the app, and an install cut off by a dropped connection picks up again by itself when the watch is back
- Screen recording: record the watch screen for up to 3 minutes from the Watch tab; videos land in Downloads/Syndesia/Recordings
- Save whole folders: long-press a folder, or use the save button above the list, to copy everything inside (subfolders included) to the phone
- Previews: look at pictures and read text files from the watch right in Syndesia; videos, music and other files open in an app on your phone

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
