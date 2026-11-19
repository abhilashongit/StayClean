# Troubleshooting

**Play Protect blocks the install.** Tap *More details → Install anyway*. See [INSTALL](INSTALL.md#why-play-protect-warns).

**"Guard" is greyed out.** *Settings → Apps → Stay Clean → ⋮ → Allow restricted settings*, then try again.

**Guard turns itself off.** Some phones stop Accessibility services to save power. Set battery to **Unrestricted** (row 5) and enable *Autostart* for Stay Clean if your phone has that option.

**The black screen shows but the app stays open.** Make sure rows 2 and 6 are **On**. Re-enable Guard, then reopen the app.

**A website was not closed.**
- Use a supported browser and make sure Guard is on.
- The filter reads the host name only; it ignores search terms on purpose.
- Open an issue with the browser, version, and the *shape* of the address (for example `word-word.tld`). Please do not post explicit links.

**A harmless site was closed.** Open an issue with the host name so the matcher can be tightened.

**Instagram says "Back in N min."** That is the 5-minutes-per-30 limit working. The interval starts at your first open.

**I cannot change settings at night.** Between 11 pm and 6 am IST this app's own settings pages close on purpose. Try after 6 am.

**Wrong time.** Phases use IST, not your local zone. Check that your network is reachable once, so the trusted clock can sync.
