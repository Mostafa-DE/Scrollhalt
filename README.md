# InstaFocus

InstaFocus is an Android accessibility service that keeps you out of the Instagram
Reels feed while the official Instagram app stays fully usable: feed, Stories, DMs,
profiles, Explore and search all work as normal.

- Tapping the **Reels tab** sends you straight back to **Home**.
- A reel someone **sends you in a DM**, or one you open from **Explore** or the
  **feed**, plays. Swiping to the next reel scrolls you back to the one you opened
  ("Only the reel you opened"). This can be switched off, in which case opening any
  reel takes you back.
- No permissions, no internet access, no data stored or sent. The accessibility
  service reads view ids (internal names like `clips_viewer_view_pager`), never
  text, captions, usernames or messages.

## Build

Requirements: JDK 17 or newer (tested with JDK 21) and the Android SDK with
platform `android-37.0` and build-tools 36.0.0.

```sh
cd InstaFocus
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME is not set
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Coverage report for the unit tests (JaCoCo, built into AGP):

```sh
./gradlew createDebugUnitTestCoverageReport
# app/build/reports/coverage/test/debug/index.html
```

Toolchain: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, Kotlin 2.4.21, Compose BOM
2026.09.00 (Material 3 1.4.0), DataStore 1.2.1. compileSdk 37, targetSdk 36,
minSdk 28.

## Install over wireless adb

1. On the phone: Settings > Developer options > Wireless debugging > on.
2. Tap **Pair device with pairing code**, then on the computer:
   ```sh
   adb pair <phone-ip>:<pairing-port>     # enter the 6-digit code
   adb connect <phone-ip>:<debug-port>    # port shown on the Wireless debugging screen
   adb devices                            # note the serial, e.g. 192.168.1.32:34079
   ```
3. Install:
   ```sh
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <serial> shell dumpsys package app.instafocus | grep versionName
   ```

## Enable the service

1. Open InstaFocus and tap **Open Accessibility settings**.
2. Find InstaFocus (on Samsung: Accessibility > Installed apps) and switch it on.
3. **If the switch is greyed out** ("Restricted setting"): Android blocks
   accessibility for apps installed outside an app store. In InstaFocus tap
   **Open App info**, then the three-dot menu at the top right >
   **Allow restricted settings**, confirm with your PIN or fingerprint, and go back
   to step 2.
4. **Samsung battery**: Settings > Battery > Background usage limits >
   **Never sleeping apps** > add InstaFocus. Otherwise One UI may stop the service.
5. The status card in InstaFocus shows **Protecting** when everything is set.
   "Enabled, not running" means Android has the service switched on but it is not
   connected; toggle it off and on.

## How detection works

Everything that decides what to do lives in `app/src/main/java/app/instafocus/detect/`,
which is plain Kotlin (no Android imports) and unit tested.

1. On each window change, content change, scroll and click from Instagram, the
   service schedules a scan (at most one every 100 ms; nothing runs while idle).
2. The scan copies the **on-screen** part of Instagram's view tree
   (`service/NodeSnapshot`). Instagram keeps every tab loaded at once, so only
   nodes that are visible to the user and have a non-empty area count.
3. `ScreenClassifier` names the screen:
   - **REEL_VIEWER**: a reel pager (`clips_viewer_view_pager`, `clips_view_pager`)
     and at least one reel control (`clips_ufi_component`, `clips_author_username`, ...).
   - **UNKNOWN**: a pager without reel controls. Never acted on.
   - **DM**, **EXPLORE**, **OTHER** by their marker ids.
   - It also reports whether the Reels tab (`clips_tab`) is visible and selected.
4. `ReelPolicy`, an immutable state machine, turns that into a decision:
   - Viewer and (Reels tab selected, or Reels tab clicked in the last 2 s):
     **GO_HOME** (taps `feed_tab`; Back from Instagram's root would close the app).
   - Viewer opened from a DM, Explore or other screen: allow it, remember the
     pager index seen in the first 1.2 s, and **SNAP_BACK** (scroll the pager back,
     or Back if that fails) when a later scroll shows a different index. With
     "Allow single shared reel" off: **BACK**.
   - At most 3 actions in 5 s, 700 ms apart; after that it pauses for 10 s and
     logs it, so an Instagram change can never cause an endless Back loop.
   - It never acts on DM, Explore or other screens.

## Updating InstagramRules after an Instagram update

All Instagram view ids are in one file:
`app/src/main/java/app/instafocus/detect/InstagramRules.kt`.

1. In InstaFocus open **Debug log** and switch on **Capture visible view ids**.
2. Open Instagram on the screen that is misdetected (for example the Reels tab),
   return to InstaFocus and tap **Copy snapshot**. The clipboard now holds the ids of
   the views that were on screen. It contains no text, usernames or messages.
3. Compare the ids with the lists in `InstagramRules` and update them. Ids match
   either in full (`com.instagram.android:id/clips_tab`) or by the part after `:id/`.
4. Add or adjust a test in `app/src/test/.../detect/`, run
   `./gradlew testDebugUnitTest`, rebuild and reinstall.

The debug log (last 200 decisions with their reasons) and the snapshot live in
memory only and are gone when the app process ends.

## Known limitations

- It depends on Instagram's internal view ids. An Instagram update can rename them
  and break detection until `InstagramRules` is updated. The current ids come from
  reference projects and have not yet been checked against Instagram 451.0.0.53.80
  on a device.
- It reacts after the reel viewer appears, so a frame or two of a reel may show
  before it leaves.
- Telling "opened from a DM / Explore / feed" apart from "Reels tab" is heuristic:
  it uses the last screen seen before the viewer and the Reels tab selection state.
- Snap-back relies on the pager reporting page indexes in scroll events. If
  Instagram stops doing that, swiping to the next reel will not be caught.
- When it is unsure, it does nothing; it prefers missing a reel over blocking a chat.
- Samsung (and Android after app updates) may turn the service off. Re-enable it
  and check "Never sleeping apps".
- No overlay is drawn; the Reels tab button stays visible.

## Manual test checklist

- [ ] Open Instagram normally: the feed loads, nothing happens.
- [ ] Tap the Reels tab: you are sent back to Home, "Reels blocked" toast.
- [ ] Open a reel someone sent you in a DM: it plays.
- [ ] Swipe up to the next reel: it scrolls back, "Only the reel you opened" toast.
- [ ] Back from that reel returns to the chat.
- [ ] Open a reel from Explore and from the feed: same as the DM case.
- [ ] Stories play and can be swiped through.
- [ ] DMs: inbox, threads, sending messages, photos and voice notes work.
- [ ] Profiles, including the reels grid on a profile, open.
- [ ] Explore and search work.
- [ ] Switch "Block Reels" off: the Reels tab works. Switch it on again: blocked.
- [ ] Switch "Allow single shared reel" off: a DM reel closes right away.
- [ ] Reboot the phone: the status card shows Protecting again without setup.
- [ ] Debug log shows the decisions; Copy snapshot puts only view ids on the clipboard.

## License and credits

GPL-3.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

- [curbox](https://github.com/curbox-app/curbox-android) (GPL-3.0): its Instagram
  detection approach and view ids were studied and adapted.
- [frosen2/remove-reels](https://github.com/frosen2/remove-reels): studied for the
  "remember the start index and snap back" idea. It has no license, so no code was
  copied.
