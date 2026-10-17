# Wi-Fi AUX Bridge

Primary phone -> Wi-Fi -> old phone -> 3.5 mm AUX -> car stereo.

The **TRANSMITTER** captures system audio with Android's official `AudioPlaybackCapture` API,
compresses it and streams it over TCP on your local network. The **RECEIVER** (old phone,
plugged into the car) decodes it and plays it with `AudioTrack` through its normal audio output.
Nothing leaves your local network; no accounts, servers or API keys.

## Build (GitHub Actions - no installs)
Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds
`app-debug.apk`; download it from the run's **Artifacts** (Actions tab).

## Build (Android Studio)
Open the folder, accept the Gradle wrapper prompt (Gradle 8.4), then
Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Use
1. Install the APK on both phones (allow "install unknown apps").
2. Old phone: **RECEIVER** > **START RECEIVER**. It shows its IP and a 4-digit code.
3. Primary phone: **TRANSMITTER**. Tap the discovered receiver (or type its IP), tap **CONNECT**,
   then **START STREAM** and approve Android's audio-capture dialog. Play music.
4. No shared Wi-Fi? On the receiver tap **Create Wi-Fi Hotspot**, join it from the other phone.

## Limits
- Not every app's audio can be captured; apps/DRM content that opt out stay silent. The app shows
  "This app does not allow its audio to be captured by Android." when capture is refused.
- Transmitter needs Android 10+; the receiver works from Android 6.0+.
- Codec is IMA ADPCM (~4:1), not Opus: Android doesn't guarantee an Opus *encoder*, and bundling
  libopus needs native binaries. High preset = ~384 kbps, Medium ~128, Low ~64.
- Discovery uses UDP broadcast; some routers block it - type the IP shown on the receiver instead.
- Hotspot uses `startLocalOnlyHotspot()`; if a device refuses, the app opens system hotspot settings.
- One transmitter at a time. Some OEM battery managers may need an exemption for long sessions.
