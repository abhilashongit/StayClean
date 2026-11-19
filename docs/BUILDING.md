# Building from source

## Requirements

- JDK 17 or newer
- Android SDK with platform 34 and build-tools 34.0.0 (`ANDROID_HOME` set, or `sdk.dir` in `local.properties`)

## Run the tests

```bash
./gradlew test
```

48 JVM tests cover the schedule, clock logic, adult matcher, tab-close labels, meter and regression checks for the accessibility configuration.

## Debug APK

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Signed release APK

Create your own key (never commit it):

```bash
keytool -genkeypair -v -keystore stayclean.jks -alias stayclean \
  -keyalg RSA -keysize 2048 -validity 10000
```

Create `keystore.properties` in the repo root (it is git-ignored):

```properties
storeFile=stayclean.jks
storePassword=YOUR_PASSWORD
keyAlias=stayclean
keyPassword=YOUR_PASSWORD
```

Then:

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release build is **unsigned** and will not install until you sign it.

> Keep your keystore safe. Android only accepts an update signed with the same key as the installed app.

## Layout notes

- `applicationId`: `com.stayclean.guard`
- `minSdk 26`, `targetSdk 34`
- No AndroidX. Fonts are bundled in `res/font`.
