# Scrollhalt

Scrollhalt is a Reels blocker for Instagram: an Android accessibility service that keeps
you out of the Reels feed while the official Instagram app stays fully usable: feed,
Stories, DMs, profiles, Explore and search all work as normal.

> Scrollhalt is not affiliated with, endorsed or sponsored by Instagram or Meta.
> Instagram is a trademark of Meta Platforms, Inc.

- The **Reels tab** button is covered so it cannot be tapped; if the tab opens anyway,
  you are sent to **Home**.
- A reel someone **sends you in a DM**, or one from the **feed** or a **profile**, plays.
  Swiping to the next reel closes the viewer.
- Reels opened from the **Explore grid** are closed at once; search, results and
  profiles found by search keep working.
- **Pause protection** for 1, 5, 10 or 15 minutes: Reels open normally until the timer
  ends, then blocking turns back on by itself (with a heads-up 10 s before).
- A **usage dashboard**: how often and how long Instagram was open today, estimated Reels
  viewing time, Reels watched, blocked attempts, and a 7-day chart.
- **Turn off for these apps**: for banking apps that refuse to run while Scrollhalt is
  on. Scrollhalt switches itself off while such an app is open and comes back 5 minutes
  later (see [below](#turn-off-for-these-apps-banking-apps)).
- No internet access, nothing sent anywhere. The only permissions are for Turn off for
  these apps, and each is optional (see below). Usage statistics stay in an
  app-private file on the phone for 30 days and are not backed up. The accessibility
  service reads view ids (internal names like `clips_viewer_view_pager`), never
  text, captions, usernames or messages.

## Build

Requirements: JDK 17 or newer (tested with JDK 21) and the Android SDK with
platform `android-37.0` and build-tools 36.0.0.

```sh
cd Scrollhalt
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

Toolchain: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, Kotlin 2.4.21 with the
serialization plugin, Compose BOM 2026.09.00 (Material 3 1.4.0), DataStore 1.3.0,
kotlinx.serialization 1.12.0. compileSdk 37, targetSdk 36, minSdk 28.

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
   adb -s <serial> shell dumpsys package app.scrollhalt | grep versionName
   ```
4. Diagnostics: `adb -s <serial> logcat -s Scrollhalt`.

**Upgrading from InstaFocus:** the app was renamed and its package changed from
`app.instafocus` to `app.scrollhalt`, so the renamed build installs as a **new app**.
Uninstall the old one (`adb -s <serial> uninstall app.instafocus`), then enable the
Scrollhalt accessibility service again, including **Allow restricted settings** and
**Never sleeping apps** (below). Settings start from their defaults.

## Enable the service

1. Open Scrollhalt and tap **Open Accessibility settings**.
2. Find Scrollhalt (on Samsung: Accessibility > Installed apps) and switch it on.
3. **If the switch is greyed out** ("Restricted setting"): Android blocks
   accessibility for apps installed outside an app store. In Scrollhalt tap
   **Open App info**, then the three-dot menu at the top right >
   **Allow restricted settings**, confirm with your PIN or fingerprint, and go back
   to step 2.
4. **Samsung battery**: Settings > Battery > Background usage limits >
   **Never sleeping apps** > add Scrollhalt. Otherwise One UI may stop the service.
5. The status card in Scrollhalt shows **Protecting** when everything is set. If it
   shows **Setup needed** although the switch is on, Android has the service enabled
   but not connected; toggle it off and on.

## How detection works

Everything that decides what to do lives in `app/src/main/java/app/scrollhalt/detect/`,
which is plain Kotlin (no Android imports) and unit tested. The signals below were
confirmed on the phone with Instagram 451.0.0.53.80, from the accessibility tree.

What it does:

- **Reels tab: hidden.** An accessibility overlay (no extra permission) covers the Reels
  tab button with a patch in the tab bar's colour (black in dark mode, white in light
  mode) and swallows taps on it ("Reels hidden by Scrollhalt"). It is placed on the
  button's on-screen bounds on every scan and removed when the tab bar is not on screen
  (threads, viewers), when a keyboard is shown, when Instagram is not in front, when
  blocking is off, and when the service stops. If the Reels tab opens anyway (a link,
  Instagram restoring it), Scrollhalt taps Home.
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
- **the reel page on screen**: the identity of the largest on-screen child of the front
  pager, recorded when the viewer opens. A different page after a pager scroll means a
  swipe (a page swapped without any scroll is Instagram's own and is just recorded, and so
  is one swapped while every scroll still reports a confirmed start index);
- **scroll events from the reel pager** (it reports its current page as `fromIndex`; only
  the pager in front scrolls, and pagers are told apart by their source node). Scrolls up
  to the first scan of the viewer, or at most 300 ms after it (no swipe lands that fast),
  are Instagram positioning the pager: their index is the confirmed start index, and any
  other index is a swipe. When the first scroll comes later, the page identity decides;
- **window changes of other apps**, only to notice that Instagram is no longer in front,
  or that an app on the Turn off for these apps list is (package name only). This is why
  the service has no package filter.

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
   logged to logcat with tag `Scrollhalt` (`adb logcat -s Scrollhalt`): activity, screen,
   selected tab, tab bar visibility, pager indexes, decision and reason. Ids and indexes
   only, never text. With **Capture visible view ids** on, or
   `adb shell setprop log.tag.Scrollhalt VERBOSE`, every scan also logs the whole tree
   (id, visible/selected flags, bounds) and every click; `setprop log.tag.Scrollhalt ""`
   turns that off.

## Messages

Scrollhalt's messages ("Reels blocked", "Reels hidden by Scrollhalt", the pause countdown and
"Reels blocking is back on") appear in a small banner below the status bar, drawn as an
accessibility overlay like the Reels cover. Toasts are not used: Android drops them for apps
whose notifications are off (Scrollhalt asks for notification permission only for the Turn off
for these apps reminder, and only when you tap Allow notifications). The banner
never takes touches, shows one message at a time, disappears after 2.5 s, shows the same
message at most every 5 s, and only appears while Instagram is in front. During the last 10 s
of a pause it stays on screen and counts down ("Reels blocking resumes in 9 s", updated every
second, only during those 10 s), then turns into "Reels blocking is back on". Opening
Instagram during those seconds shows the countdown at once.

## Pause and protection states

The status card shows one of five states: **Protecting** (Block Reels on), **Paused**
(a timed pause runs, with a countdown and **Resume now**), **Blocking off** (the Block
Reels switch is off; tap the card to turn it on), **Off for &lt;app&gt;** (see
[Turn off for these apps](#turn-off-for-these-apps-banking-apps)) and **Setup needed** (the
accessibility service is off or not connected).

- **Pause protection** on the Protecting card opens a sheet with 1, 5, 10 and 15 minutes.
  The pause is stored with its wall-clock end, its `elapsedRealtime` end and the boot
  count. In the same boot elapsed time decides, so changing the clock does not shorten or
  extend it; after a reboot the wall clock decides and the remaining time is capped at
  the chosen duration.
- The service ends the pause with a timer and also checks on every Instagram event and
  whenever the app comes to the front. No exact-alarm permission is used.
- Switching Block Reels off during a pause cancels the pause.
- If a reel is on screen when the pause ends, it finishes, whatever it was opened from,
  also on the Reels tab feed. The first swipe to another reel afterwards closes it: Back
  for a single reel, the Home tab on the Reels tab. Resuming is not a new opening, so even a
  swipe right at the end of the pause counts. Opening the Reels tab again later is blocked
  as usual. Neither the start nor the end of a pause can make Scrollhalt escalate to the
  launcher.

## Turn off for these apps (banking apps)

Some banking apps refuse to run while an accessibility app installed outside an app store is
switched on ("Detected Application(s): Scrollhalt"). Scrollhalt does not try to hide from
that check. Instead it really switches its accessibility service off while such an app is
open, and comes back afterwards.

1. In Scrollhalt, **Turn off for these apps** opens a list of the apps with a launcher icon
   (with a search field). Tick the banking apps. The list is empty by default.
2. When one of them comes to the front (the service reads only the package name of the
   app in front, never its screen), Scrollhalt stores when it switched off and when it comes
   back, sets an alarm 5 minutes later, removes the Reels cover and banner, closes the usage
   statistics (the time it is off counts as not tracked) and switches its service off
   (`disableSelf()`). Logcat (`adb logcat -s Scrollhalt`) says which app it switched off for.
3. After 5 minutes:
   - With the one-time adb grant below, it **turns itself back on**: it adds itself to
     Android's list of enabled accessibility services, keeping the others.
   - Without it, it posts a notification **"Scrollhalt is off"**; tapping it opens
     Accessibility settings, where you switch Scrollhalt on. Without notification permission
     nothing is posted, and the Scrollhalt screen shows the state.
4. When the service is back and the banking app is still in front, it switches off again
   for another 5 minutes. It never acts on that app's screen.

The main screen then shows **Off for &lt;app&gt;** with **Back on at HH:MM** and
**Turn back on now** (direct with the grant, otherwise it opens Accessibility settings). The
Turn off for these apps row says **Turns back on by itself** with the grant, or **Reminds
you to turn it back on**, with an **Allow notifications** button: that is the only place
Scrollhalt asks for notification permission.

One-time grant, so Scrollhalt can turn itself back on (with the phone connected over adb,
see [Install over wireless adb](#install-over-wireless-adb)):

```sh
adb -s <serial> shell pm grant app.scrollhalt android.permission.WRITE_SECURE_SETTINGS
adb -s <serial> shell dumpsys package app.scrollhalt | grep WRITE_SECURE   # granted=true
```

It stays granted across reboots and updates, and is removed when Scrollhalt is uninstalled.
Without it everything works, but you turn Scrollhalt back on yourself after the reminder.

The comeback alarm is inexact (no exact-alarm permission), so in deep sleep Android may
deliver it a few minutes late. After a reboot it is set again; the wall clock then decides,
capped at 5 minutes, like a pause.

Permissions, all only for this feature:

- `WRITE_SECURE_SETTINGS`: turn the accessibility service back on by itself. Android grants
  it only over adb (the command above); an app can never get it on its own.
- `POST_NOTIFICATIONS`: the "Scrollhalt is off" reminder when it cannot turn itself back on.
  Asked for only from the Allow notifications button, never at start.
- `RECEIVE_BOOT_COMPLETED`: set the comeback alarm again after a reboot while one is pending.

To list the apps it uses a `MAIN`/`LAUNCHER` package visibility query, not
`QUERY_ALL_PACKAGES`. Still no internet permission.

## Usage statistics: how they are measured

Everything is computed on the phone by plain-Kotlin reducers in
`app/src/main/java/app/scrollhalt/stats/` (unit tested) and stored as JSON in an
app-private DataStore file: one summary per day for 30 days, plus what is in progress.
There is no per-event history. Durations use `elapsedRealtime`; days are local calendar
days, and time over midnight is split between the two days.

- **Instagram opened / sessions**: a session starts when Instagram comes to the front;
  screens inside Instagram never count. It ends when another app or the launcher comes to
  the front, or the screen goes off. Coming back within 10 s continues the same session
  (no new opening; the seconds away are not counted). The keyboard, the notification
  shade, Quick Settings, the volume panel and permission dialogs do not end a session,
  and their time counts as Instagram time. A session over midnight counts as a session
  on both days; its opening counts on the first.
- **Instagram time**: total, average per session and longest session of the day.
- **Reels viewing (estimated)**: time while the Reels tab or a single reel viewer is on
  screen, whether blocking is on, paused or off, split by those three modes and by Reels
  tab vs single reels (DM, feed, profile, Explore). A viewer on screen is not proof that a
  video played. Viewers shorter than 1 s add time but do not count as viewing sessions.
- **Reels watched**: the reel a viewer opened on, plus every swipe to another reel that
  was not blocked (a new page index from the pager, or another page on screen).
- **Blocked today**, per category, each as detected, action taken (Back, Home or the
  launcher was pressed and the press succeeded) and confirmed (the next screen was no
  longer the blocked one). Retries of the same attempt are never counted again.
  - Reels tab: taps on the cover (one attempt per second of tapping; a tap on the cover
    is acted on and confirmed by definition) and Instagram opening on the Reels tab.
  - Explore Reels: one per Explore reel closed.
  - Next-Reel swipes: one per allowed reel that a swipe closed; Instagram positioning the
    pager while opening a reel is not a swipe.
  - Times Scrollhalt had to go to the launcher are counted separately (in the details).
  - During a pause or with Block Reels off nothing is blocked; swipes count as watched.
- **Tracked**: the time the service ran. A day without it shows as "Not tracked", never
  as zero.
- Writes are batched: changes at most every 30 s, the last-seen time at most every 60 s
  while Instagram is open, and at once when Instagram leaves the front, the screen goes
  off or on, a pause starts or ends, or the service stops. If the process dies, open
  sessions are closed at the last write.
- **Reset statistics** (menu) deletes all days; settings and a running pause stay.

## Updating InstagramRules after an Instagram update

All Instagram view ids are in one file:
`app/src/main/java/app/scrollhalt/detect/InstagramRules.kt`.

1. In Scrollhalt open **Debug log** and switch on **Capture visible view ids**. The log
   also shows, for every scan, the screen, the selected tab and the activity, and for
   every pager scroll its `fromIndex`, `toIndex`, `itemCount` and which pager it was.
2. Open Instagram on the screen that is misdetected (for example the Reels tab),
   return to Scrollhalt and tap **Copy snapshot**. The clipboard now holds the ids of
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
- Usage numbers are estimates. Reels time is time a Reels viewer was on screen, not proof
  of playback. Videos playing inline in the feed and Stories are not counted at all.
- Statistics are only collected while the service runs; time after the last write before
  the process is killed (at most about a minute) is lost.
- With Instagram closed and the phone asleep, the end of a pause is applied at the next
  Instagram event (the timer cannot fire in deep sleep). There is no visible difference:
  that first event switches blocking back on before Scrollhalt looks at the screen.
- A shared reel closed because "Shared Reels play once" is off is not counted in any
  Blocked category.
- Like the blocking itself, all statistics depend on Instagram's view ids.
- Turn off for these apps: if Scrollhalt is force-stopped while it is off, Android drops
  its comeback alarm; open Scrollhalt and tap Turn back on now. Turning itself back on
  writes Android's accessibility settings; whether every Android version binds the service
  again from that write is only known from testing on the phone.

## Manual test checklist

- [ ] Open Instagram normally: the feed loads, nothing happens.
- [ ] The Reels tab button is covered; tapping it does nothing ("Reels hidden" banner).
- [ ] Press Home: the cover disappears. Reopen Instagram: it comes back.
- [ ] Open a reel someone sent you in a DM: it plays.
- [ ] Swipe up to the next reel: the viewer closes ("Reels blocked" banner).
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
- [ ] Pause for 1 minute: the card turns amber and counts down; Reels open normally; a
      banner counts down "Reels blocking resumes in 10 s" to 1 s, then says "Reels blocking
      is back on".
- [ ] Pause while watching a Reel: when the pause ends the Reel keeps playing and the
      next swipe closes it. On the Reels tab feed the current Reel keeps playing too and the
      next swipe taps Home.
- [ ] Resume now ends a pause; switching Block Reels off during a pause cancels it.
- [ ] Open and leave Instagram: "Instagram opened" and "Instagram time" go up; leaving and
      coming back within 10 s does not add an opening.
- [ ] Tap the covered Reels tab: "Reels tab" in Blocked today goes up by one per burst.
- [ ] Reset statistics clears the numbers; the pause and settings stay.
- [ ] Debug log shows the decisions; Copy snapshot puts only view ids on the clipboard.
- [ ] Turn off for these apps: tick a banking app; open it: it runs, Scrollhalt shows
      "Off for &lt;app&gt;" with the comeback time. With the adb grant it is back after 5 minutes
      (and off again if the app is still open); without it the reminder appears.
- [ ] Turn back on now brings Scrollhalt back (or opens Accessibility settings).

## License and credits

GPL-3.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

- [curbox](https://github.com/curbox-app/curbox-android) (GPL-3.0): its Instagram
  detection approach and view ids were studied and adapted.
- [frosen2/remove-reels](https://github.com/frosen2/remove-reels): studied for the
  "remember the start index of the opened reel" idea. It has no license, so no code was
  copied.
