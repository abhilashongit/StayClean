# Privacy

Short version: **Stay Clean does not collect, store, or send your data.** There is no server behind it.

## Permissions, and why

| Permission | Why |
|---|---|
| Accessibility service | To see which app or browser address is in front, so it can close it. Reads only the app name and the address bar. |
| Display over other apps | To show the black STAY CLEAN cover. |
| Usage access | Backup detection of the foreground app if Accessibility stops. |
| Device admin | Makes uninstalling a deliberate step. |
| Foreground service, notifications | Keeps the guard alive and visible. |
| Battery optimisation exemption | Stops the system from putting the guard to sleep at night. |
| Exact alarms, boot completed | Wake at phase boundaries; resume after restart. |
| Kill background processes | Closes the blocked app completely. |
| Internet | A single `HEAD` request to `google.com` / `cloudflare.com` to read the time from the `Date` header. No cookies, no identifiers, no body. |

## What stays on the phone

Settings, the Instagram minute counter and a short in-memory diagnostics line. Backups are disabled (`allowBackup=false`).

## What never happens

No analytics, no crash reporting, no ads, no accounts, no logging of the sites you visit. Addresses are matched in memory and discarded.

## Verify it

The source is here. Search for `HttpURLConnection` in `Util.java`: it is the only network code in the app.
