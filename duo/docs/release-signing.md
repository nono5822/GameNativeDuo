# Release signing

Android accepts an update only when it is signed with the same key as the installed app. Debug builds use
`duo/debug.keystore`, which is in the repository on purpose (CI and local debug builds update each other): anyone can
sign with it, so it must never sign an APK that is published. Release builds use a private key that only the
maintainer and the repository's secrets hold.

## How the build chooses a key

| Build | Key |
|---|---|
| `:app:assembleModernDebug` | `duo/debug.keystore` (public) |
| `:app:assembleModernRelease` | The key named by `DUO_RELEASE_KEYSTORE` (a path), `DUO_RELEASE_STORE_PASSWORD`, `DUO_RELEASE_KEY_ALIAS`, `DUO_RELEASE_KEY_PASSWORD` (environment variables or Gradle properties). Without them the APK is **unsigned** and cannot be installed |

A debug build and a release build have the same application id and different keys, so one does not install over the
other. `-PduoDebugSuffix` builds the debug APK as `app.gamenative.duo.debug`, which installs next to a release. It is
off by default until a game has been launched under that id on a device (paths come from `AppPaths`, and the prebuilt
native libraries match on `app.gamenative`, so it should work); the adb paths in `CLAUDE.md` and `duo dev --device`
use `app.gamenative.duo`.

## One-time setup (maintainer)

1. Make the key, on your own PC, outside the repository:

   ```bash
   keytool -genkeypair -keystore ~/.config/gamenative-duo/release.jks -alias gamenative-duo \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=GameNative Duo"
   ```

2. Keep an offline copy of the file and its passwords. If the key is lost, no update can ever install over a
   published APK again: every user would have to uninstall first, which deletes their downloaded games.
3. Add four repository secrets (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `DUO_RELEASE_KEYSTORE_BASE64` | `base64 -w0 ~/.config/gamenative-duo/release.jks` |
   | `DUO_RELEASE_STORE_PASSWORD` | the keystore password |
   | `DUO_RELEASE_KEY_ALIAS` | `gamenative-duo` |
   | `DUO_RELEASE_KEY_PASSWORD` | the key password (leave out when it is the keystore password) |

4. Publish the certificate's fingerprint in the README, so people can check a download:

   ```bash
   keytool -list -v -keystore ~/.config/gamenative-duo/release.jks -alias gamenative-duo | grep SHA256
   ```

## Making a release

Push a tag like `duo-v1.4.0`. `.github/workflows/duo-release.yml` builds the release APK, refuses to continue if it is
unsigned or signed with the debug key, prints the certificate's SHA-256 in the run summary, and attaches the APK to a
**draft** release. Try the APK on a device, write the notes, then publish the draft.

To check any APK: `apksigner verify --print-certs GameNativeDuo.apk`.

## Moving from a debug-signed install

An app installed from a debug build cannot be updated by a release build: uninstall it first. That deletes downloaded
games and saves that are not in the cloud, so say it in the first release's notes.
