# OpenHouse Open Source Notes

This repository is prepared for public development as an OpenHouse fork of Termux.

## License

The base Termux application is licensed under GPLv3 only. OpenHouse changes inside this repository are published under the same GPLv3-only terms so the combined work stays license-compatible.

See:

- [LICENSE.md](../LICENSE.md)
- [termux-shared/LICENSE.md](../termux-shared/LICENSE.md)

## Not Official Termux

This project is not an official Termux release. Do not present OpenHouse APKs as official Termux builds.

The debug APK may be signed with the public untrusted Termux test key that upstream already documents. Treat it as a development/testing artifact, not a production trust anchor.

## What Should Not Be Committed

Do not commit:

- `local.properties`
- APK outputs
- Gradle build outputs
- private signing keys
- real API keys or provider tokens
- local maintenance logs
- device-specific configuration

The repository ignores common local outputs, including `openhouse-preview/`.

## Build

```bash
./gradlew :app:assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

## Install To ADB Device

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

## Release Checklist

Before publishing a release:

1. Run `git status --short`.
2. Confirm no real secrets are present.
3. Build the APK.
4. Record the APK SHA256.
5. Install on a real device.
6. Open the maintenance center.
7. Confirm one-click stage list renders individual stages.
8. Confirm the online maintenance source loads.
9. Confirm OpenCode, Codex, and Claude Code stages are present.
10. Upload the APK as a release artifact only if it is intentionally public.

