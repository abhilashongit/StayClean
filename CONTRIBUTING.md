# Contributing

Thank you for helping. Small and kind is the spirit here.

## Good first contributions

- New phrases for `Phrases.java` (short, gentle, never shaming)
- Notes on how other browsers behave (`Targets.java`, `TabClose.java`)
- Doc translations

## Before you open a PR

```bash
./gradlew test assembleDebug
```

- Keep logic that decides *whether to block* free of Android imports so it stays unit-testable.
- Add or update a test in `app/src/test/java/com/stayclean/`.
- Never commit keystores or `keystore.properties`.
- No analytics, no extra network calls.

## Reporting bugs

Please include Android version, phone model, browser and version, and what you expected. For adult-site misses, describe the address shape rather than pasting explicit links.
