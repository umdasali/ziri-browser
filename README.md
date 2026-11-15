# Ziri Browser (Android, Kotlin)

Browse. Download. Enjoy. A native Android browser: Jetpack Compose UI, system WebView, Room, and an
OkHttp download engine with a foreground service.

## Build

1. Install **Android Studio** (latest stable, with AGP 9.5 support) and let it install the Android SDK (API 36).
2. **File → Open** this folder (`ziri-browser-android`). Gradle sync downloads Gradle 9.9.1 and all libraries.
3. Pick a device or emulator (Android 10 / API 29 or newer) and press **Run**.

Command line, after Android Studio has created the Gradle wrapper (or run `gradle wrapper` once):

```
./gradlew assembleDebug
```

## Layout

- `data/` Room entities, DAOs, settings (DataStore)
- `browser/` `TabsManager` (one WebView per tab), URL helpers, private-tab profile
- `download/` `DownloadEngine` (queue, pause/resume via HTTP Range, MediaStore output), foreground service, notifications
- `ui/` Compose screens: home, browser, tabs, bookmarks, history, downloads
