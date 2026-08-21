# MeshLink (Android)

Offline peer-to-peer messaging for nearby Android devices using Google Nearby Connections.

## Requirements

- JDK 17
- Android SDK 35
- Gradle 8.7 (wrapper included)
- Google Play Services on physical devices

## Build

From this `android` directory:

```sh
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Open this folder in Android Studio to install on a device. Create a local `local.properties` with your SDK path if missing:

```properties
sdk.dir=/path/to/Android/sdk
```

## Usage

1. Install on two physical Android phones (emulator Nearby support is limited).
2. Grant Bluetooth, location, and nearby-device permissions.
3. Set a display name, tap **Start Discovery**, then **Connect** on a peer.
4. Exchange messages offline; history is stored locally in SQLite.
