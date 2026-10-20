# InstaFocus

InstaFocus is an Android accessibility service that keeps you out of the Instagram
Reels feed while the official Instagram app stays fully usable: feed, Stories, DMs,
profiles, Explore and search all work as normal.

- The **Reels tab** button is covered so it cannot be tapped; if the tab opens anyway,
  you are sent to **Home**.
- A reel someone **sends you in a DM**, or one from the **feed** or a **profile**, plays.
  Swiping to the next reel closes the viewer.
- Reels opened from **Explore** (or anywhere in the Search tab) are closed at once;
  search itself keeps working.
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
confirmed on the phone with Instagram 451.0.0.53.80, from the accessibility tree.

What it does:

- **Reels tab: hidden.** An accessibility overlay (no extra permission) covers the Reels
  tab button with a patch in the tab bar's colour (black in dark mode, white in light
  mode) and swallows taps on it ("Reels hidden by InstaFocus"). It is placed on the
  button's on-screen bounds on every scan and removed when the tab bar is not on screen
  (threads, viewers), when a keyboard is shown, when Instagram is not in front, when
  blocking is off, and when the service stops. If the Reels tab opens anyway (a link,
  Instagram restoring it), InstaFocus taps Home.
- **Explore grid reels: closed at once** (Back). What counts is where the reel was opened
  from: the last screen before the viewer. From the Explore grid (Search tab, grid cells
  `image_preview` on screen), or from an unknown screen inside the Search tab, it closes.
  The search bar, results, profiles, photo posts and the grid itself keep working.
- **Reels shared in DMs, and reels from the feed or a profile** (also a profile reached
  through search): the first one plays; the
  first swipe to another reel presses Back, which closes the viewer. With "Allow reels
  shared in DMs" off, a shared reel closes right away.
- It never acts on DM threads, the inbox or other screens.

Signals:

- the **tab buttons** (`feed_tab`, `clips_tab`, `direct_tab`, `search_tab`,
  `profile_tab`): on screen on tab roots, gone inside threads, viewers and modals. In the
  accessibility tree there is no `tab_bar` node; the buttons sit directly under the root;
- which tab is **selected**. A tab reports isSelected only after a tab switch; after a cold
  start none does, and then the Reels page being on screen marks the Reels tab. Pages of
  the main tab ViewPager other than the current one are not visible to accessibility, so
  the Reels tree kept alive behind other screens does not count. When a viewer hides the
  tab bar, the tab selected at the last visible tab bar is used;
- the **origin screen**: the last confidently classified non-reel screen (`EXPLORE_GRID`,
  `PROFILE` via `profile_viewpager` / `profile_tab_layout` /
  `row_profile_header_imageview_frame_layout`, `DM`, `OTHER`). UNKNOWN frames never reset it;
- the current **activity**, only as a positive signal: a reel shared in a DM opens in
  `TransparentModalActivity`;
- **DM markers** (`direct_thread_header`, `row_thread_composer_edittext`, ...) and
  **shared-reel markers** (`clips_sent_by`, `reel_viewer_message_composer`,
  `reply_bar_container_scroll_view`, `sender_username_or_fullname`; ids only, their text
  is never read);
- **scroll events from the reel pager** (it reports its current page as `fromIndex`; only
  the pager in front scrolls, and pagers are told apart by their source node);
- **window changes of other apps**, only to notice that Instagram is no longer in front
  (package name only). This is why the service has no package filter.

Flow:

1. Window changes, content changes, scrolls and clicks from Instagram schedule a scan
   (at most one every 100 ms; nothing runs while idle). The scan copies the tree
   (`detect/TreeCopy`, skipping the insides of hidden nodes).
2. `ScreenClassifier` names the screen: **REELS_TAB**, **SINGLE_REEL** (pager and reel
   controls on screen, no tab bar), **DM**, **UNKNOWN** (pager without controls),
   **EXPLORE_GRID**, **PROFILE** or **OTHER** (feed, search results, everything else).
3. `ReelPolicy`, an immutable state machine, decides:
   - Every attempt is blocked, however often you retry. Actions are at least 700 ms
     apart; a decision held back by that cooldown is retried with a scan at its end.
   - Escalation instead of giving up: an action is *ineffective* when the first scan after
     the cooldown still shows the same blocked screen. Each rung is tried twice:
     Reels tab: tap Home tab, then the launcher (`GLOBAL_ACTION_HOME`). Reel viewer: Back,
     then the launcher. Leaving to another screen, leaving Instagram, or a new tap on the
     Reels tab (a new attempt, not a failure) resets it.
   - Only if the launcher itself fails 3 times in 10 s does it pause for 10 s and log
     "detection may be broken". Effective actions never cause a pause.
   - Before any action the service checks that Instagram is still in front, so a late
     event can never press Back or Home in another app.
4. For adb diagnosis, actions, screen changes, cover moves and leaving Instagram are
   logged to logcat with tag `InstaFocus` (`adb logcat -s InstaFocus`): activity, screen,
   selected tab, tab bar visibility, pager indexes, decision and reason. Ids and indexes
   only, never text. With **Capture visible view ids** on, or
   `adb shell setprop log.tag.InstaFocus VERBOSE`, every scan also logs the whole tree
   (id, visible/selected flags, bounds) and every click; `setprop log.tag.InstaFocus ""`
   turns that off.

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

- It depends on Instagram's internal view ids and layout. An Instagram update can rename
  them and break detection until `InstagramRules` is updated.
- The Reels cover is a coloured patch. It may look slightly off if Instagram changes its
  tab bar colour or layout, and it can lag a frame behind animations (it follows the
  button on every scan).
- It reacts after a viewer appears, so a frame of a reel may show before Back, and a frame
  of the next reel can show before a swipe is closed.
- A reel opened inside the Search tab from a screen it does not recognise (neither the
  grid nor a profile) is closed, to be safe.
- The cover colour is a constant sampled from Instagram 451 (`TAB_BAR_COLOR_DARK` =
  #0C0F14; light mode #FFFFFF is not confirmed yet), never read from the screen.
- Reels from the feed and profiles are assumed to behave like DM reels; not every path
  has been captured on a device yet.
- Swipe detection relies on the pager reporting page indexes in scroll events.
- When it is unsure, it does nothing; it prefers missing a reel over blocking a chat.
- Samsung (and Android after app updates) may turn the service off. Re-enable it
  and check "Never sleeping apps".

## Manual test checklist

- [ ] Open Instagram normally: the feed loads, nothing happens.
- [ ] The Reels tab button is covered; tapping it does nothing ("Reels hidden" toast).
- [ ] Press Home: the cover disappears. Reopen Instagram: it comes back.
- [ ] Open a reel someone sent you in a DM: it plays.
- [ ] Swipe up to the next reel: the viewer closes ("Reels blocked" toast).
- [ ] Back from that reel returns to the chat.
- [ ] Open a reel from the Explore grid: it closes at once. Search, results and profiles work.
- [ ] Search a profile and open one of its reels: it plays; a swipe closes it.
- [ ] Open a reel from the feed: it plays; a swipe closes it.
- [ ] Stories play and can be swiped through.
- [ ] DMs: inbox, threads, sending messages, photos and voice notes work.
- [ ] Profiles, including the reels grid on a profile, open.
- [ ] Explore and search work.
- [ ] Switch "Block Reels" off: the cover goes and the Reels tab works. On again: covered.
- [ ] Switch "Allow reels shared in DMs" off: a DM reel closes right away.
- [ ] Reboot the phone: the status card shows Protecting again without setup.
- [ ] Debug log shows the decisions; Copy snapshot puts only view ids on the clipboard.

## License and credits

GPL-3.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

- [curbox](https://github.com/curbox-app/curbox-android) (GPL-3.0): its Instagram
  detection approach and view ids were studied and adapted.
- [frosen2/remove-reels](https://github.com/frosen2/remove-reels): studied for the
  "remember the start index of the opened reel" idea. It has no license, so no code was
  copied.
