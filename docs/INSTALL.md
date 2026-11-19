# Install Stay Clean on Android

Needs **Android 8.0 (API 26) or newer**. About 1 MB. Takes two minutes.

## 1. Download

On your phone, open the [latest release](https://github.com/abhilashongit/StayClean/releases/latest) and tap **stay-clean-v1.3.apk**.

Prefer to check it first? The SHA-256 of the file is listed in [`dist/SHA256SUMS.txt`](../dist/SHA256SUMS.txt).

## 2. Allow the install

Android blocks installs from outside the Play Store until you allow them for the app you downloaded with (Chrome, Files, etc.).

- Tap the downloaded file → if asked, **Settings → Allow from this source** → go back → **Install**.

## 3. Play Protect warning {#why-play-protect-warns}

You may see *"Blocked by Play Protect"* or *"App scan recommended"*.

**Why:** Stay Clean asks for the Accessibility permission (it needs it to see which app is open) and is signed with its own key rather than a Play Store key. Play Protect is cautious about that combination, and warns first.

**What to do:** tap **More details → Install anyway**. If you only see *OK*, open **Play Store → profile → Play Protect → ⚙ → turn off "Scan apps with Play Protect"**, install, then turn it back on.

## 4. Finish setup (in this order)

Open **Stay Clean**. Under **SETUP**, work down the list. Each row turns to **On** when done.

| # | Row | What to do |
|---|-----|-----------|
| 1 | Notifications | Allow. Keeps the guard visible and alive. |
| 2 | Cover screen | Allow *Display over other apps*. |
| 3 | Backup guard | Grant *Usage access*. A second guard that takes over if the first stops. |
| 4 | Lock uninstall | Activate *Device admin*. Uninstalling now needs this removed first. |
| 5 | Stay alive | Set battery to **Unrestricted**. |
| 6 | **Guard** | Turn on the *Stay Clean* Accessibility service. **Do this last.** |

### If Guard is greyed out

Android 13+ hides Accessibility for apps installed outside the Play Store until you allow it:

1. **Settings → Apps → Stay Clean**
2. Tap **⋮** (top right) → **Allow restricted settings**
3. Return to Stay Clean and tap **Guard** again.

## 5. Optional: Night lock

Flip **Night lock** on to hold Instagram and X shut from 11 pm to 5 am. Once it is on during the night, it cannot be turned off until morning.

## Updating

Install the newer APK over the old one. Your settings stay. Updates only work if the APK is signed with the same key as the one installed, so download releases from this repository only.

## Uninstalling

Because Device Admin is on, go to **Settings → Security → Device admin apps → Stay Clean → Deactivate** first (this is blocked between 11 pm and 6 am), then uninstall normally.
