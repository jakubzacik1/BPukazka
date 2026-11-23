# Impulse Blocker

A personal, offline Android app that makes it hard to open selected
food-delivery apps and websites on impulse. Temporary access is still
possible, but only after a waiting period, a math challenge and a final
confirmation, and only for the one app or website that was requested.

No ads, accounts, analytics or servers. Everything stays on the phone.

* Kotlin, Jetpack Compose (Material 3, dark theme), DataStore (JSON)
* minSdk 29 (Android 10), target/compile SDK 36, tested target: Pixel 9 Pro
* No root, no third-party services, no extra libraries beyond AndroidX + kotlinx

---

## Build status (read first)

| Part | Status |
|---|---|
| `:core` module (domain rules, DNS packet handling, math, timers, unlock state machine, config/import validation) | **Compiled and tested: 26 JUnit tests, 0 failures** |
| `:app` module (Android services + Compose UI) | **Written but NOT compiled yet.** The cloud build environment's network policy blocked `dl.google.com` / `maven.google.com`, which serve the Android SDK, the Android Gradle Plugin, AndroidX and Compose. |
| Debug APK | **Not built yet** (same reason) |

To build the APK, run this on a machine with the Android SDK, or in an environment that can reach `dl.google.com`:

```bash
cd ImpulseBlocker
./gradlew :core:test assembleDebug
# APK: ImpulseBlocker/app/build/outputs/apk/debug/app-debug.apk
```

Android Studio: *File → Open → `ImpulseBlocker/`*, let it sync, then *Build → Build APK(s)*.

The pure-logic tests can run without the Android SDK:

```bash
./gradlew -p tools/core-only-build :core:test
```

---

## Project layout

```
ImpulseBlocker/
├── core/                      Pure Kotlin/JVM, unit-tested
│   ├── DomainRules.kt         URL → domain normalisation, subdomain matching, DoH host list
│   ├── DnsMessage.kt          DNS question parsing, NXDOMAIN/SERVFAIL synthesis, TTL clamping
│   ├── UdpPacket.kt           IPv4/IPv6 UDP parsing + reply building with checksums
│   ├── DnsFilter.kt           Block / forward decision per query
│   ├── MathChallenge.kt       Random question sets (+ − × ÷), 4 difficulty levels
│   ├── UnlockState.kt         Monotonic deadlines, challenge stages, per-target unlocks
│   └── Config.kt              Config model, "is this change weakening?" policy, import sanitising
└── app/
    ├── BlockerApp.kt          Wiring, shutdown checkpoint receiver
    ├── data/                  DataStore repositories, photo store, SAF export/import
    ├── service/
    │   ├── AppBlockerAccessibilityService.kt   app blocking (event-driven)
    │   ├── DnsVpnService.kt + DnsTunnelEngine.kt  local DNS filter (VpnService)
    │   ├── BootReceiver.kt    restart after reboot / app update
    │   └── Notifications.kt
    └── ui/                    Dashboard, app picker, settings, challenge, blocking screen
```

---

## How it works

### App blocking: AccessibilityService

* The service subscribes only to `TYPE_WINDOW_STATE_CHANGED` events. It does
  not read window content (`canRetrieveWindowContent=false`), key events or
  gestures. Android pushes an event whenever a window comes to the front, so
  there is **no polling loop**.
* If the event's package is on the blocked list and has no active temporary
  unlock, `BlockActivity` is started on top. It runs in its own task,
  is excluded from Recents, and Back / "Leave" go to the home screen.
* **Duplicates:** `BlockActivity` is `singleTask` and repeat events for the same
  package within 600 ms are ignored. A real re-entry (e.g. from Recents) still re-blocks.
* **Rapid switching:** every event updates the foreground package, and system UI and keyboard windows are ignored.
* **Screen lock/unlock:** on `USER_PRESENT` / `SCREEN_ON` the last foreground app is checked again.
* **Unlock expiry:** a single `Handler` timer fires at the next expiry. If the unlocked app is still in front, it is blocked immediately.
* **Already-running apps:** the check runs whenever such an app comes back to the front, or when the state changes.
* **Never blocked:** the phone/dialer, emergency apps, Telecom, System UI, Settings,
  permission controller, package installer, launchers, keyboards and this app.
  They are hidden from the picker, and the service also refuses to block them.
* After a reboot, Android rebinds enabled accessibility services by itself.

### Website blocking: local DNS filter (VpnService)

* The VPN claims only two virtual addresses: DNS server `10.111.222.2` and
  `fd6b:6c6f:636b::2` (/32 and /128 routes). Android sends DNS lookups there.
  **All other traffic goes directly over Wi-Fi/mobile (split tunnel).** That
  preserves normal connectivity and keeps the battery cost close to zero.
* `DnsTunnelEngine` sleeps in `poll()` until a packet arrives. Then:
  * If the queried name equals or is a subdomain of a blocked rule, it answers
    **NXDOMAIN** at once. The answer carries no SOA record, so it isn't cached
    negatively. The browser shows "site can't be reached".
  * Otherwise the query is forwarded over a `protect()`ed UDP socket to the
    **DNS server of the current network**. It is the same server Android would use without the app.
  * Answers for temporarily unlocked domains get TTL ≤ 30 s, so blocking returns quickly after expiry.
* IPv4 and IPv6 DNS are both handled. The app excludes itself from its own VPN to avoid loops.
* `setMetered(false)` stops Android from treating the VPN as metered.
* **Encrypted DNS:** a setting (on by default) blocks well-known DoH/DoT hostnames such as `dns.google` and `cloudflare-dns.com`.
  Firefox's canary domain `use-application-dns.net` always gets NXDOMAIN, which turns off Firefox's automatic DoH.
  The dashboard warns when Android **Private DNS** is set to a specific hostname, because that bypasses any VPN DNS.
* **Blocked state:** a DNS filter cannot show a custom web page (HTTPS makes that impossible).
  Instead, the app posts a notification: *"Blocked: wolt.com – tap to request a temporary unlock"* (at most once per minute per domain).
* **No external library:** for DNS-only filtering, about 400 lines of tested packet code were simpler and safer than a full user-space TCP/IP stack.

### Background operation

| Situation | What keeps protection running |
|---|---|
| UI closed / Home / app switch | Both services are bound by the system and don't depend on the UI |
| Removed from Recents | Same; the system bindings keep the process alive. The block screen is excluded from Recents. |
| Screen locked / unlocked | Event-driven, plus an explicit re-check on `USER_PRESENT` |
| Reboot | Accessibility is rebound by Android. The VPN is restarted by `BootReceiver`, by the accessibility service as a backup, and by Android if *Always-on VPN* is set (recommended). |
| App update | `MY_PACKAGE_REPLACED` restarts the VPN |
| VPN revoked / another VPN | `onRevoke()` posts a high-priority warning and the dashboard shows the reason |
| Accessibility turned off | The service posts a warning when it is unbound, and the dashboard shows it |

The VPN runs as a foreground service with a minimal ongoing notification
("Website blocking is active"). Android requires this; it is not hidden.

### The unlock challenge

1. **Photo:** your personal photo is shown prominently on the block screen and the challenge.
2. **Waiting period (default 15 min):** stored as a deadline on `SystemClock.elapsedRealtime()`
   plus the boot count. It keeps running while the screen is locked or the
   app is closed, and **changing the date/time does not affect it**.
   * Clean shutdown: the remaining time is saved and resumes after boot.
   * Crash or battery pull: the wait starts over.
   * A reboot can never skip the wait.
3. **Math (default 10 questions, Hard):** a mix of + − × ÷ with integer
   division, generated from `SecureRandom`, so the sequence is never the same.
   One wrong answer restarts the math step with new questions. Leaving the
   screen during math also restarts the questions, but not the wait.
4. **Confirmation:** "Do you still want to open X for 30 minutes?"
5. **Access:** only that app package or that domain rule is unlocked, for the
   configured duration (default 30 min). Its deadline is monotonic too. After a clean reboot
   it resumes with the remaining time; after a crash the unlock ends.
   Every other app and site stays blocked.

**Websites:** tap *Unlock* next to the domain on the dashboard, or tap the
"Blocked: …" notification. After confirming, reload the page.

**Protected changes:** removing an app or domain from the list was an obvious
bypass. So were disabling blocking, shortening the wait, or other weakening
changes. With *Protect changes* on (default), any change that **weakens**
protection requires the same challenge, which then opens a 10-minute editing
window. Changes that make blocking stricter are always allowed, and the first
setup (empty lists) is free. You can still turn off the accessibility
service or VPN in Android settings. The app does not fight that; it warns you.

### Settings

Waiting period (on/off, minutes) · math (on/off, number of questions, difficulty) ·
unlock duration · protect changes · block encrypted-DNS providers ·
blocking-screen photo (system Photo Picker: change/remove) · photo in Android backup (off by default).

### Persistence, backup, restore

* Configuration: `files/config/blocker_config.json`. It is included in Android Auto Backup and device-to-device transfer.
* Runtime state (unlocks, running challenge): `no_backup/`. It is never backed up, so a restore can't carry an unlock over.
* Photo: a private downscaled JPEG in `no_backup/photo/`. It moves to `files/photo_backup/` (backed up)
  only if you turn on *Include photo in Android backup*.
* **Export / import** (Settings → Backup): a JSON file through the system file picker (Storage
  Access Framework). The photo is included **only** if you tick the box, and a warning
  explains what that means. Imports are validated and sanitised. An import that would weaken protection
  needs the challenge first.
* **Reinstall:** Android restores the Auto Backup if backup is enabled on the
  phone (*Settings → System → Backup*) and the same Google account is used.
  Otherwise, import your exported file. Without either, uninstalling deletes the configuration.
  Re-grant the accessibility and VPN permissions after reinstalling; Android never restores them.

---

## Android limitations (honest list)

* **Accessibility for sideloaded apps (Android 13+):** the switch may be greyed
  out as a *restricted setting*. Fix: *Settings → Apps → Impulse Blocker → ⋮ → Allow restricted settings*,
  then enable the service. The dashboard shows this hint.
* **A flash of the blocked app** can be visible for a fraction of a second
  before the block screen appears. The accessibility event comes after the window is shown.
* **Android can stop accessibility services** under extreme memory pressure. The
  dashboard and a notification report "enabled but not running".
* **Only one VPN can be active.** Starting another VPN app stops website blocking, and you get a warning.
* **Private DNS = specific hostname** bypasses the filter. Use *Automatic* or *Off*.
  *Automatic* works: Android tries DoT to the VPN's DNS IP, that fails, and it falls back to plain DNS through the filter.
* **Bypasses the DNS filter cannot see:** browsers or apps with built-in DNS-over-HTTPS
  that reach the resolver by a hard-coded IP, apps with hard-coded server IPs, and
  existing open connections (already-loaded pages may keep working until the connection closes).
  Rules cover only the domains you enter. `wolt.com` does **not** cover `wolt.fi`, other
  regional domains, or ordering through Google Maps or other platforms.
* **Delivery apps are not blocked by the DNS filter.** App blocking handles them.
  Blocking an app's API domains as websites would also break it, which is intended if you add them.
* **"Block connections without VPN"** (lockdown) must stay off, because this VPN routes only DNS.
* **Clean-shutdown detection** relies on `ACTION_SHUTDOWN`. On a crash or a battery pull, waits restart and unlocks end (fail closed).
* The DNS engine handles UDP DNS only. TCP DNS (used for very large answers) to the virtual IP is not forwarded, so such rare lookups fail.

---

## Install on the Pixel 9 Pro

1. Build `app-debug.apk` (see *Build status*), then copy it to the phone or use `adb`:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Without adb: open the APK in *Files*, allow *Install unknown apps* for that app when asked, and install.
3. Open **Impulse Blocker**:
   1. Turn on **App blocking**. Use *Accessibility settings → Impulse Blocker → On*. If the switch is greyed out, use *App info → ⋮ → Allow restricted settings* first.
   2. Turn on **Website blocking** and accept the VPN request.
   3. **Allow notifications** and **unrestricted battery** (dashboard buttons).
   4. Recommended: *Settings → Network & internet → VPN → ⚙ Impulse Blocker → Always-on VPN: ON*. Keep *Block connections without VPN* **OFF**.
   5. Check that Private DNS is *Automatic* or *Off*.
4. Tap **+** to add the delivery apps, and add the domains (e.g. `wolt.com`, `foodora.sk`, `ubereats.com`, `bolt.eu` – whatever you use, each regional variant separately).
5. Settings → choose your photo. Lists are free to edit while empty. After that, weakening changes need the challenge, so set your preferences first.

---

## Tests

### Automated (executed)

`./gradlew -p tools/core-only-build :core:test` → **26 tests, 0 failures** (run in the build environment):

* `DomainRulesTest`: URL/wildcard/port/IDN normalisation, invalid input, subdomain-only matching (`notwolt.com` and `wolt.com.evil.net` are not matched), most-specific rule.
* `DnsTest`: question parsing, truncated-input safety, NXDOMAIN format, TTL clamping with compressed names, IPv4/IPv6 UDP round trip with checksum verification, TCP and fragment rejection, filter decisions (blocked domain, subdomain, unlocked domain, other TLD, DoH provider, Firefox canary).
* `MathTest`: count, all four operations present, integer division, non-negative subtraction, differing sequences, answer parsing.
* `UnlockLogicTest`: the full flow with no step skippable, disabled steps skipped, expiry exactly at 30 min, a second app stays blocked while the first is unlocked, shutdown resume, unclean-reboot fail-closed, settings edit window.
* `ConfigTest`: weakening detection, export/import round trip, import sanitising, foreign-file rejection, strict defaults.

### On-device checklist (NOT executed: no device or emulator was available)

| # | Scenario | Expected |
|---|---|---|
| 1 | Open a blocked app | Block screen with photo, "X is blocked", *Request temporary unlock*, *Leave X* |
| 2 | Open a non-blocked app | Nothing happens |
| 3 | Open `wolt.com` in Chrome | "This site can't be reached" (NXDOMAIN) and a "Blocked: wolt.com" notification |
| 4 | Open `www.wolt.com` / `m.wolt.com` | Blocked (subdomain) |
| 5 | Swipe the app away in Recents, open a blocked app | Still blocked; VPN key icon still present |
| 6 | Lock the screen inside an unlocked app until expiry, then unlock | Block screen appears after unlocking |
| 7 | Reboot | App blocking works after unlocking the phone; VPN key icon returns |
| 8 | Wrong math answer | "Wrong answer…", new questions from question 1 |
| 9 | Waiting timer finishes (also with the app closed or the clock changed) | Math step appears; changing the clock has no effect |
| 10 | Temporary unlock expires while the app is open | Block screen appears within ~1 s |
| 11 | App A unlocked, open blocked app B | B is blocked |
| 12 | Disconnect the VPN in Settings | Warning notification; dashboard shows "Stopped" |
| 13 | Turn off the accessibility service | Warning notification; dashboard shows "Accessibility permission is OFF" |
| 14 | Export, change config, import | Config restored; photo only if chosen |
| 15 | Uninstall and reinstall (backup on) | `adb shell bmgr backupnow app.impulseblocker`, reinstall, config restored; re-grant permissions |

Useful commands:
```bash
adb logcat -s AppBlocker DnsVpn DnsTunnel
adb shell bmgr backupnow app.impulseblocker
```
