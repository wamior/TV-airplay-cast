# TV-airplay-cast

Receive casts from your phone on an Android TV (APK) or a Windows PC.

Every push to `main` builds and publishes a release with `tv-airplay.apk` and `tv-airplay-desktop.zip`.

## Status

| Protocol | Android TV | Windows |
|---|---|---|
| AirPlay screen mirroring (H.264 + AAC-ELD/LC audio) | implemented, untested on hardware | implemented (video; audio depends on Chromium's AAC-ELD support), untested |
| AirPlay video-URL hand-off | optional mode (`AirPlay mode` button), untested | no |
| DLNA / UPnP MediaRenderer | implemented, untested | implemented, untested |
| Google Cast | no (the real protocol needs Google-signed receiver credentials) | no |
| ALAC audio (plain music AirPlay) | no | no |

By default the receiver advertises mirroring only (no AirPlay "video" feature), so apps like TikTok mirror the
screen instead of handing over a video URL that cannot be played.

## Install

TV: `adb install tv-airplay.apk`, open the app once (it starts the receiver service) and allow
"display over other apps" if offered.

PC: unzip `tv-airplay-desktop.zip`, run `TV AirPlay Cast.exe`, allow it through the Windows firewall.

## Layout

- `app/` - Android app (Kotlin). `PlaybackHub` plays DLNA/URL video with ExoPlayer, `mirror/` decodes mirrored
  frames with MediaCodec. `net/` has the DLNA stack and the legacy AirPlay video receiver.
- `app/src/main/cpp/` - native AirPlay protocol: the library from [UxPlay](https://github.com/FDH2/UxPlay)
  (pairing, FairPlay, RAOP/mirroring), libplist, and a JNI bridge (`airplay_jni.c`).
- `desktop/` - Electron app (DLNA in JS, mirroring via `core/airplay_cli.c`, a small helper process built from
  the same UxPlay sources).

## License

GPL-3.0-or-later (the UxPlay protocol code is GPL-3.0). libplist is LGPL-2.1.
The release APK is signed with the key in `keystore/` on purpose: it is a public sideload key so that builds
update over each other; do not treat the signature as proof of origin.
