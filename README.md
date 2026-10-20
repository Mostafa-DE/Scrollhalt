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
which is plain Kotlin (no Android imports) and unit tested. The signals below were
confirmed on Instagram 451.0.0.53.80.

The key fact: Instagram keeps the Reels tab (its `clips_viewer_view_pager`, controls
and video, flagged visible and full screen) alive behind every other screen, including
DM threads. So the reel tree being present proves nothing. InstaFocus instead uses:

- the bottom **tab buttons** (`feed_tab`, `clips_tab`, `direct_tab`, `search_tab`,
  `profile_tab`): on screen on tab roots, gone inside threads, viewers and modals. In the
  accessibility tree on 451 there is no `tab_bar` node; the buttons sit directly under the root;
- which tab is **selected**. A tab reports isSelected only after a tab switch; after a cold
  start none does, and then the Reels page being on screen (pages of the main tab
  ViewPager other than the current one are not visible to accessibility) marks the Reels tab;
- the current **activity** from window-state events, only as a positive signal: a reel
  shared in a DM opens in `TransparentModalActivity`. The main screens report
  `InstagramMainActivity` in events (dumpsys shows `MainTabActivity`), so nothing else
  depends on the activity name;
- **DM markers** (`direct_thread_header`, `row_thread_composer_edittext`, ...) and
  **shared-reel markers** (`clips_sent_by`, `reel_viewer_message_composer`,
  `reply_bar_container_scroll_view`, `sender_username_or_fullname`; ids only, their text
  is never read);
- **scroll events from the reel pager** (ViewPager2 reports the current page as
  `fromIndex`, and only the pager in front scrolls).

Flow:

1. Window changes, content changes, scrolls and clicks from Instagram schedule a scan
   (at most one every 100 ms; nothing runs while idle). The scan copies the on-screen
   part of the view tree (`service/NodeSnapshot`).
2. `ScreenClassifier` names the screen:
   - **REELS_TAB**: tab buttons on screen and `clips_tab` selected (or no tab selected and
     the Reels pager on screen), in any activity.
   - **SINGLE_REEL**: a reel pager and reel controls on screen, no `tab_bar`, and
     `clips_tab` not selected. Marked *shared in DM* when the activity is
     `TransparentModalActivity` or a shared-reel marker is on screen. Covers DM reels and
     reels opened from Explore, the feed or a profile.
   - **DM**: a DM marker on screen (checked before the non-DM single-reel case, so a DM
     thread with the Reels tree behind it is never treated as a reel).
   - **UNKNOWN** (pager without controls) and **OTHER**: never acted on.
3. `ReelPolicy`, an immutable state machine, turns that into a decision:
   - REELS_TAB, or a click on `clips_tab`: **GO_HOME** (taps `feed_tab`; Back from
     Instagram's root would close the app).
   - SINGLE_REEL: allow it and remember the start page from pager scroll events in the
     first 1.2 s, tracked per pager (two pagers can exist at once). A later scroll of that
     pager to another page: **SNAP_BACK** (scroll the same pager back; Back if that fails).
   - With "Allow single shared reel" off: **BACK** right away for a reel shared in a DM;
     for other reels it waits for the first swipe and then presses Back, because without
     a DM signal it cannot be sure enough that a reel is really in front.
   - Every attempt is blocked, however often you retry. Actions are at least 700 ms
     apart; a decision held back by that cooldown is retried with a scan at its end, so a
     fast re-tap cannot slip through.
   - Escalation instead of giving up: an action is *ineffective* when the first scan after
     the cooldown still shows the same blocked screen. Each rung is tried twice:
     Reels tab: tap Home tab, then the launcher (`GLOBAL_ACTION_HOME`). Single reel:
     snap back, then Back, then the launcher. Leaving to any other screen, or a new tap on
     the Reels tab after our action (a new attempt, not a failure), resets it.
   - Only if the launcher itself fails 3 times in 10 s (so detection is probably broken)
     does it pause for 10 s and log "detection may be broken". Effective actions never
     cause a pause.
   - It never acts on DM or other screens.
   - For adb diagnosis, every action and screen change is logged to logcat with tag
     `InstaFocus` (`adb logcat -s InstaFocus`): activity, screen, selected tab, tab bar
     visibility, pager indexes, decision and reason. Ids and indexes only, never text.
     With **Capture visible view ids** on, or `adb shell setprop log.tag.InstaFocus VERBOSE`,
     every scan also logs the whole snapshot (id, visible/selected flags, bounds) and every
     click event; `adb shell setprop log.tag.InstaFocus ""` turns that off again.

## Updating InstagramRules after an Instagram update

All Instagram view ids are in one file:
`app/src/main/java/app/instafocus/detect/InstagramRules.kt`.

1. In InstaFocus open **Debug log** and switch on **Capture visible view ids**. The log
   also shows, for every scan, the screen, the selected tab and the activity, and for
   every pager scroll its `fromIndex`, `toIndex`, `itemCount` and which pager it was.
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
  and break detection until `InstagramRules` is updated. The ids and activities were
  captured from Instagram 451.0.0.53.80; feed- and profile-opened reels are assumed to
  behave like Explore-opened ones (not captured yet).
- It reacts after the reel viewer appears, so a frame or two of a reel may show
  before it leaves.
- Telling a single reel apart from the Reels tab relies on the tab bar and the
  selected tab. Another full-screen surface without a tab bar (for example stories)
  sits in front of the kept-alive Reels pager too; it is treated as a single reel, which
  is harmless because nothing happens until that pager scrolls.
- With "Allow single shared reel" off, reels opened from Explore, the feed or a profile
  play until the first swipe, then close.
- Snap-back relies on the pager reporting page indexes in scroll events. If
  Instagram stops doing that, swiping to the next reel will not be caught.
- The app declares package visibility for Instagram (a `<queries>` entry, not a
  permission) so it can tell activities from dialogs in window events.
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
