# How it works

Stay Clean is a plain Java app with no third-party libraries. The logic that decides *whether to block* is pure Java and unit-tested without a phone.

## The pieces

| Piece | Role |
|---|---|
| **GuardService** (Accessibility) | Sees window changes, decides, and covers the screen. Primary guard. |
| **WatchdogService** (foreground) | Backup guard using Usage Access and a cover overlay if Accessibility stops. |
| **Schedule** | Phase maths in IST: ACTIVE, TAMPER, DAY. |
| **TrustedClock** | Network-time anchor (HEAD to Google/Cloudflare, `Date` header) combined with the boot clock. |
| **Targets** | Package names of watched apps and browsers; the decision table. |
| **Adult** | Host-only matcher: tokens, prefixes and TLDs. Ignores search queries. Avoids lookalikes such as *essex* or *analysis*. |
| **TabClose / Closer** | Finds the right control in Chrome's UI and closes the tab, in layers. |
| **UsageMeter** | Instagram's 5-minutes-per-30 counter, saved across restarts. |
| **Phrases** | Shuffle-bag of 25 food lines and 14 social lines. |
| **AdminReceiver** | Device Admin, so uninstall needs a deliberate step. |

## Phases (India Standard Time)

| Phase | Window | Behaviour |
|---|---|---|
| ACTIVE | 22:55 – 05:05 | Guard fully awake, watching every event. |
| TAMPER | 05:05 – 06:00 | Awake only to hold the settings-page lock until 6. |
| DAY | otherwise | Event filter narrowed to the watched apps; the rest sleeps. |

Blocks inside ACTIVE:

- **Food apps:** 00:00 – 05:00
- **Instagram + X** (toggle on): 23:00 – 05:00
- **Tamper lock** on this app's settings pages: 23:00 – 06:00
- **Adult sites and the Instagram meter:** all day

Exact alarms wake the service at phase boundaries, and a boot or time-change receiver re-arms them.

## A block, step by step

1. A watched app comes to the foreground.
2. The decision table says "block".
3. A black overlay covers the screen at once (a single-flight gate prevents stacking).
4. **STAY CLEAN** fades in with one line from the shuffle-bag.
5. The app is sent Home and its background process is killed.

## The adult filter

For supported browsers the guard reads the address bar (view-ids are reported via `flagReportViewIds`) and a fallback scan of visible text. Only the **host** is examined. If it matches, a black cover goes up silently and `Closer` tries, in order:

1. Long-press the tab-switcher button → **Close tab**
2. ⋮ menu → **Close tab**
3. Open the tab grid and close the current card
4. Back twice
5. Home + kill the browser process

This works in Incognito because it acts on the UI, not on browsing history.

## Clock safety

Blocking is the **OR** of the system clock and the trusted clock: if either says "blocked", it is blocked. Turning off automatic time or setting the date forward does not open the window.

## Known limits

Be realistic. This is a deterrent, not a security boundary.

- Safe Mode, `adb`, or a factory reset can remove it.
- By day you can switch off Accessibility or Device Admin. That is deliberate, so the app cannot lock you out of your own phone.
- Browser interfaces change. The closer has fallbacks, but a future Chrome update may need a patch. Reports welcome.
- The adult filter is a name-based heuristic, not a content scanner. It will miss some sites and, rarely, flag a harmless one.
- Only listed browsers are watched (see `Targets.java`).
- Android and manufacturer battery managers can kill background services. Set battery to *Unrestricted* and, on aggressive skins, enable autostart for Stay Clean.
