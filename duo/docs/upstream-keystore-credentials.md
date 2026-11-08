# Upstream proposal: encrypt every store's credentials with the Android Keystore

Devkit item 4 ("Code trust", hardening) asks for this as an upstream change: it protects every GameNative user, not
just people who install mods, and the code it touches is upstream's. Nothing here is applied in this fork (golden rule:
upstream files get one-line hooks only).

## The problem

A Windows game, and any mod inside it, runs as the app's Linux user. The drive map (`Z:` points at the image root
only, `WineUtils.java`) does not contain it: Wine's `\\?\unix\` paths, P/Invoke into Wine's Unix side, or raw
syscalls from native code reach any file the app can read. So a file under `files/` is as readable to a game as it is
to the app.

What the app keeps there today (checked on the fork's base, 2026-11-08):

| Store | Where | Protected |
|---|---|---|
| Steam access and refresh tokens | DataStore, `access_token_enc` / `refresh_token_enc` (`PrefManager.kt`) | yes, AES key in the Android Keystore (`Crypto.kt`) |
| EA | `EaConstants.CREDENTIALS_FILE` (`EaAuthManager.kt`) | yes, `Crypto.encrypt` |
| Epic | `files/epic/credentials.json` (`EpicAuthManager.kt`) | **no**, plain JSON |
| GOG | the auth config (`GOGAuthManager.getAuthConfigPath`, written with `writeText`) | **no**, plain JSON |
| Amazon | `files/amazon/credentials.json` (`AmazonAuthManager.kt`) | **no**, plain JSON |
| Epic launch tokens | `drive_c/users/Public/Documents/EpicTokens` in the prefix, for the game's launch | short-lived by design (the game needs them) |

The Keystore key never leaves the Keystore: decrypting needs a binder call to the keystore service from the app,
which code inside Wine has no practical way to make. A plain file needs nothing.

## The change

1. Epic, GOG and Amazon read and write their credentials through `Crypto.encrypt` / `Crypto.decrypt`, like EA does
   (`EaAuthManager.save` / `load` are a ready pattern: encrypt the JSON bytes, discard an unreadable file).
2. Migration on first read: a file that parses as plain JSON is re-written encrypted (as `PrefManager` did for the
   Steam tokens: "Converting old access token to encrypted").
3. Tools that need the file in plain form (if a store's CLI reads `credentials.json` directly) get a decrypted copy in
   `cacheDir` only for the call, deleted afterwards. Check each store's launcher before changing its format.
4. Optional, later: an AES-GCM transformation for new data (`Crypto` uses CBC/PKCS7; fine with a random IV, but GCM
   authenticates).

## Limits

It raises the bar a lot; it is not a sandbox. A malicious mod still runs as the app's user and can act while the game
runs: use the game's own session, read the game's files, the launch tokens the game is given. That is why code mods are
reviewed, built from source, signed and revocable (`duo/docs/community-packages.md`), and why the consent screen says
"same access as your games".

## How to offer it

A small PR per store (Epic, GOG, Amazon), each with its migration and a unit test of the round trip, referencing the
EA implementation. No fork-specific code; useful for upstream's `.gold` builds too.
