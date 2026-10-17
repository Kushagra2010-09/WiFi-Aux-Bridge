# Wi-Fi AUX Bridge

Stream your main phone's audio over Wi-Fi to an old phone that is plugged into your car's AUX jack.

Main phone -> Wi-Fi -> old phone -> AUX cable -> car stereo

## Download
Go to the **Releases** page (right side of this repo), open the newest release, and download `app-debug.apk`. Install it on both phones.

## How to use
1. Put both phones on the same Wi-Fi.
2. Old phone: open the app, tap RECEIVER, then START RECEIVER. It shows an IP address and a pairing code.
3. Main phone: open the app, tap TRANSMITTER, pick the receiver from the list (or type its IP), tap CONNECT, then START STREAM and approve the audio-capture prompt.
4. Play music. It comes out of the old phone.

No shared Wi-Fi? On the receiver, tap Create Wi-Fi Hotspot and join it from the main phone.

## Limits
- The main phone needs Android 10 or newer. The old phone can be Android 6 or newer.
- Some apps block audio capture, and their audio won't play.
- Audio is compressed with ADPCM, not Opus.
