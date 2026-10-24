# TV-airplay-cast

Android TV app (Kotlin) that turns the TV into a wireless receiver for video sent from a phone.

## Status

| Protocol | State |
|---|---|
| DLNA / UPnP MediaRenderer (BubbleUPnP, VLC, Windows "Cast to device", most Android gallery apps) | implemented, untested on hardware |
| AirPlay 1 URL video (`/play`, `/scrub`, `/rate`, `/stop`, `/playback-info`) | implemented, untested on hardware |
| AirPlay screen mirroring | not implemented (needs the FairPlay handshake + H.264/AAC stream receiver) |
| Google Cast | not implemented (the real Cast protocol requires Google-signed receiver credentials) |

## Build

CI builds a debug APK on every push (Actions -> latest run -> artifact `tv-airplay-cast-debug`).

Locally: open the folder in Android Studio, or run `gradle :app:assembleDebug` with the Android SDK installed.

## Install on the TV

```
adb connect <tv-ip>
adb install app-debug.apk
```

Open the app once so it starts the receiver service. Grant "display over other apps" if the app offers it
(needed to open the player when a cast starts while another app is in front).

## Layout

- `PlaybackHub` - one ExoPlayer sink shared by all protocols
- `net/DlnaRenderer`, `net/SsdpServer`, `net/UpnpServices` - UPnP stack
- `net/AirPlayServer` - AirPlay HTTP receiver; mDNS advertising lives in `CastService`
- `net/MiniHttpServer` - tiny HTTP/1.1 server used by both
