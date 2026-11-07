# Phonon for Android

A system text-to-speech engine for Android built on **Phonon**, Gradium's neural voices that
run **offline**, on the phone itself. Once installed it shows up as a system TTS engine and works
with **TalkBack** and every app that speaks through Android's text-to-speech API.

- The `phonon-40m-multilingual` model and the catalog's 102 voices are **bundled in the APK**:
  nothing to download.
- Languages: Portuguese (Brazil), English (UK, US and character voices), Spanish (Spain and
  Mexico), French and German.
- The system and TalkBack speech rate and pitch work (through Sonic), plus an optional extra
  speed multiplier.
- Speech stops instantly when interrupted, leading silence is trimmed, and each language has a
  selectable default voice.
- Built-in response-time measurement, and an optional faster (q8_0) audio decoder.
- The interface is in English, with a Portuguese translation.
- Needs Android 7 or later on a 64-bit ARM processor. The APK carries two builds of the native
  library, an optimized one (ARMv8.2, nearly every phone since 2019) and a compatible one, and
  picks one automatically.

## Install

1. Open this repository's **Releases** page on the phone and download the latest `.apk`.
2. Open the file and allow installing from unknown sources if Android asks.
3. Open the **Phonon** app once: on first use it prepares the model, which takes a few seconds.
4. In **Settings > Accessibility > Text-to-speech output** (the path varies by manufacturer),
   choose **Phonon** as the preferred engine. The app has a button that opens that screen.

## How it is built

Everything runs in GitHub Actions (`.github/workflows/build.yml`):

1. Checks out Gradium's runtime, [gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts),
   at a pinned commit, and applies the small patches in `patches/`.
2. Downloads the model and voice pack, kept as an asset of this repository's `model-pack`
   release (it is too large for git).
3. Quantizes the weights to q8_0 (smaller and faster on phones) and checks that synthesis works.
4. Builds `libptts_ffi.so` for arm64 with Rust and the Android NDK, in both variants.
5. Builds and signs the APK with Gradle.

Every push to `main` produces an APK as a workflow artifact. A `v*` tag (for example `v0.2.0`)
publishes a release with the APK, using `release-notes/<tag>.md` as its description when present.

Signing uses the `KEYSTORE_B64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` secrets. Without them the
APK is signed with a throwaway key, and updates no longer install over the previous version.

## Licenses

- This app's code: MIT.
- Phonon runtime (gradium-ai/xn-ptts): MIT or Apache 2.0.
- **Model `gradium/phonon-40m-multilingual`: CC BY-NC 4.0, non-commercial use only.** This app
  may not be sold or used commercially while it ships that model.
- Voices `gradium/phonon-voices`: Apache 2.0.
- Sonic (Bill Cox), used for speed and pitch: Apache 2.0.

Full texts are in `licenses/`. Phonon is made by Gradium and builds on Kyutai's Pocket TTS and
Mimi. This is an independent project, not affiliated with Gradium. The voice-to-language list comes
from Andre Louis's Phonon add-on for NVDA.
