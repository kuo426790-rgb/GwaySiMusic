# GwaySi Music

A native Android local music player project built with Kotlin. The project can be built from an Android phone using GitHub Actions.

## Included
- Local audio library with search and mood filtering
- Album artwork in the song list and player
- Playback controls, seek bar, shuffle and repeat
- Five-band Android Equalizer with per-song saved settings
- Mood creation and song assignment
- GitHub Actions workflow that builds and uploads a debug APK

## Build on an Android phone
1. Create or use a GitHub repository and upload these project files.
2. Open **Actions** and run **Build GwaySi Music APK** (or push a commit to `main`).
3. Open the successful run and download artifact `GwaySi-Music-debug-apk`.
4. Extract the APK and install it. Android may ask permission to install unknown apps.

This project uses Java 17 and Gradle 8.9 in GitHub Actions. The APK has not been built or device-tested in this chat; run the workflow to verify compilation and test playback on your phone.
