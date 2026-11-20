# reddit18: the adult-community list

Builds `app/src/main/res/raw/reddit18.bin`, the list the Reddit community guard checks.

- **Source:** communities Reddit itself flags 18+ (`over18`), read from the public
  [Arctic Shift](https://github.com/ArthurHeitmann/arctic_shift) archive. Reddit closed anonymous lookups in 2026,
  so the app ships a list instead of asking live.
- **Scope:** every flagged community with at least 1,000 members. Large ordinary communities are pulled too,
  only to test that nothing ordinary is caught.
- **Format:** first 4 bytes of SHA-256 of each lowercase name, sorted and Golomb-Rice coded. The app holds no
  readable names. Communities the name rules in `rules.py` already catch are not stored.
- **Rules:** `rules.py` must match `RedditGuard.java` (words, allowlist, the "sfw" escape).

Rebuild before a release:

```
python3 pull.py 1000          # adult-flagged communities -> arctic.json
python3 pull_sfw.py 10000     # ordinary communities, for checks -> sfw.json
python3 pack.py ../../app/src/main/res/raw/reddit18.bin
```

`pack.py` refuses to write if any allowlisted or ordinary community would match. The JSON files and any
plaintext lists stay out of git.
