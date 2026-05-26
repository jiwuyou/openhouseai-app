# Contributing To OpenHouse

Keep changes scoped and easy to review.

## Development Rules

- Preserve upstream Termux behavior unless the OpenHouse feature explicitly needs a change.
- Keep OpenHouse-specific code and assets named clearly.
- Do not hard-code API keys, login tokens, or provider credentials.
- Prefer dynamic maintenance source updates for stage text and script flow when an APK rebuild is not needed.
- Run a debug build before submitting code changes.

## Useful Commands

```bash
git status --short
./gradlew :app:assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

