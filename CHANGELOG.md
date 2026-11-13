# Changelog

## 1.16 — 2026-11-12 · Select several

- Select several files: in the phone's Files tab (your watch's storage), hold a file or folder to start selecting, then tap more. Save them all to the phone, move or copy them to another folder on the watch (open the folder, then tap Move here or Paste here), or delete them together. With one picked, More… shows rename, preview and install
- On the watch, Send to phone works the same way: hold a file to pick several, then send them all to your phone or delete them
- Update both the phone app and the watch app to get these

## 1.15.3 — 2026-11-12 · Search fix

- Fixed: jumping to Private space could still open the app drawer's search. Syndesia now waits for the app list to open, flicks only inside it, stops as soon as it reaches the bottom (an extra flick there was opening search), and closes search if it pops up anyway
- Update the phone app to get this

## 1.15.2 — 2026-11-12 · No more search

- Fixed: jumping down to Private space tapped the app drawer's search bar. The flicks now start in the middle of the app list
- Update the phone app to get this

## 1.15.1 — 2026-11-12 · Faster jump to Private space

- Private space and Private apps really jump to the bottom of the app list now: a few quick flicks instead of the list's slow page-by-page scroll
- Update the phone app to get this

## 1.15 — 2026-11-12 · Faster Private space

- Unlock phone with the screen off: Syndesia now waits for the screen to come on, then swipes up and types your PIN (it tries the swipe a few times if the lock screen isn't ready)
- Private space and Private apps zip straight to the bottom of the app list instead of scrolling slowly
- Private space now switches the fingerprint prompt to your PIN and types it, so you don't have to touch the sensor
- Private apps only lists apps in the Private section (below its header), and leaves out the Add/Install tile
- Update the phone app to get these

## 1.14 — 2026-11-12 · Private apps

- Private apps: a new button in the watch remote lists the apps in your phone's Private space and opens the one you tap. It opens Private space for you first, typing your saved PIN if it asks (or use your fingerprint)
- Private space and Unlock no longer tap the Private header when Private space is already open, so they can't lock it by accident
- Update both the phone app and the watch app

## 1.13 — 2026-11-12 · Phone files

- Phone files: a new button on the watch opens Arkheia on your phone's files, so you can browse your phone from your wrist and copy files to the watch, over Bluetooth
- On the phone, Settings › Watch remote › Browse phone files from the watch turns on All files access, which it needs. If it's off, the phone shows a notification the first time the watch asks
- Needs Arkheia 1.7 or newer on the watch. Update both the phone app and the watch app

## 1.12 — 2026-11-12 · Private space

- Private space: a new button in the watch remote opens Private space on your phone. It opens the app list, scrolls to Private and taps it, and types your saved PIN if it asks (or use your fingerprint)
- Unlock phone: the swipe now starts at the very bottom edge, so it no longer lands on the fingerprint sensor and stops
- Wake watch Wi-Fi: the watch now reports back what actually happened. Syndesia tells you if Wi-Fi came on, if it's switched off on the watch (with a notification on the watch to turn it on), or if there's no saved network nearby
- Wake watch Wi-Fi asks for any Wi-Fi network, not only ones with internet, so the watch brings Wi-Fi up more readily
- Update both the phone app and the watch app to get this

## 1.11.2 — 2026-11-12 · Unlock fix

- Fixed: Unlock phone saying it couldn't find the PIN pad. Android hides the PIN pad from ordinary accessibility services; the watch remote is now declared as an accessibility tool so it can see and press the keys
- Fixed: pressing Unlock with the PIN pad already open closed it. Syndesia now types straight into an open PIN pad, and otherwise swipes up on the lock screen like you would
- Update both the phone app and the watch app. If it still can't see the lock screen, turn the Syndesia watch remote off and on in Accessibility settings

## 1.11.1 — 2026-11-12 · Unlock without a watch lock

- Unlock phone no longer needs your watch to have a screen lock
- Update both the phone app and the watch app to get this

## 1.11 — 2026-11-11 · Unlock from watch

- Unlock phone: a new button in the watch remote wakes your phone and types its PIN on the lock screen
- Save the PIN on the phone in Settings › Watch remote › Unlock from watch. It's encrypted with a key kept in the phone's secure hardware and never sent to the watch
- Only works while your watch has a screen lock, so a watch someone picks up off a table can't unlock your phone
- A wrong saved PIN is tried once and not repeated, so it can't lock your phone out
- Update both the phone app and the watch app to get this

## 1.10 — 2026-11-11 · Air mouse

- Air mouse: move your wrist to move the cursor, and pinch finger and thumb together to click. Works on the computer mouse and the phone remote touchpads; tap the sensor button at the top to turn it on
- Two ways to move: only while a finger rests on the screen (the default, so the cursor stays put when you're not using it), or always on, where holding the screen drags
- Two quick pinches make a double-click. Right-click stays on the touchpad's button
- Air mouse settings on the watch (Computer mouse › Air mouse): speed, flip left/right or up/down, pinch on or off, and Calibrate pinch, which learns how firmly you pinch
- Update the watch app from the Install tab to get it

## 1.9 — 2026-11-11 · Computer mouse

- Computer mouse: your watch becomes a Bluetooth mouse and keyboard for your computer. Pair it once like any wireless mouse, nothing to install on the computer
- Touchpad: drag to move, tap to click, hold then drag to select or move things, crown scrolls, and a right-click button
- Keyboard: type from the watch, plus Enter, Backspace, Tab, Esc, arrow keys, and copy, paste, undo, select all and Alt+Tab
- Backup: if your watch can't be a Bluetooth mouse, your phone can be, with the watch driving it (Settings › Computer mouse on the phone)
- Update the watch app from the Install tab to get it

## 1.8.3 — 2026-11-11 · Hotspot connect fix

- Fixed: the watch joining the phone's hotspot but Syndesia not connecting. Syndesia now searches the watch for its debugging port directly, since the watch's usual announcement doesn't reach the phone over its own hotspot
- If Wireless debugging switches itself off on the hotspot, Syndesia now says so and how to fix it
- Syndesia will offer to update the watch app over Bluetooth first

## 1.8.2 — 2026-11-11 · Hotspot timing fix

- Fixed: the phone's hotspot switching off before the watch could join it, which also made a new hotspot appear on every try
- The watch now asks "Connect to …?" straight away instead of after 15 seconds
- If a try doesn't work, the hotspot stays on for two minutes so trying again uses the same network
- Syndesia will offer to update the watch app over Bluetooth first

## 1.8.1 — 2026-11-11 · Smoother mouse

- Smoother mouse: the cursor glides between updates instead of jumping, the watch sends movement twice as often, and acceleration ramps up gently
- Smooth crown scrolling: one continuous finger that follows the crown and lets go without flinging, instead of separate swipes
- Tap and hold, then drag: hold on the touchpad to press and keep holding on the phone, for long-press menus, selecting text, or dragging icons and sliders. The watch buzzes and the pad lights up while you're holding
- Taps click straight away (two quick taps make a double tap)
- Update both the phone app and the watch app to get this

## 1.8 — 2026-11-11 · Watch remote

- Phone remote on the watch: Back, Home, Recents, notifications, Quick Settings, screenshot, lock, power menu and split screen
- Mouse: drag on the watch to move a cursor on the phone, tap to click, hold for a long press, double-tap, and turn the crown to scroll
- Media and volume controls, plus a torch switch
- Open any phone app from the watch, and type into the phone's selected text box
- Turn it on in Settings › Watch remote on the phone (it's an accessibility service). Update the watch app to get the Remote screen

## 1.7.2 — 2026-11-11 · Hotspot joining fix

- Fixed: the watch getting stuck joining the phone's hotspot. If it doesn't join on its own within 15 seconds, the watch now asks you to tap Connect, and Syndesia tells you on the phone
- Syndesia will offer to update the watch app over Bluetooth first

## 1.7.1 — 2026-11-11 · Hotspot fix

- Fixed: Connect via phone hotspot spinning forever when the watch app was too old. Syndesia now checks the watch app's version first and offers to update it
- Update the watch app over Bluetooth, no Wi-Fi needed: from the hotspot prompt, or the watch card on the Install tab
- Each hotspot step now gives up with a clear message instead of waiting forever

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
