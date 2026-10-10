# Totune

Record your voice, then tune it and mix it on your Android phone (Android 10+).

## Features
- **One tile per effect**: tap a tile's name to switch it on/off, turn its single knob, tap ⋯ for every detail
- **Vocoder**: your voice drives a synth — chords from your key, melody robot, monotone or deep robot
- **Grit** (saturation) and **Chorus**
- **Style presets**: Hip-hop/Trap (Black & Yellow, Astro Rage, Mask Off…), Pop, Overuse Autotune, Robot/Vocoder
- **Record** vocals alone or over an imported beat (use headphones), or import an existing audio file
- **TUNE**: pitch correction with key (12 keys), scale (major, minor, chromatic, harmonic minor, pentatonics, blues, modes), retune speed (0 ms robotic → 400 ms natural), amount, A4 reference (415–466 Hz, default 440), transpose, and "Detect key from my voice"
- **Pitch graph**: sung pitch vs tuned pitch over the scale's notes; tap to seek
- **EQ**: low cut, high cut, low/high shelves and a mid band, with a live curve
- **COMP**: threshold, ratio, attack, release, makeup
- **FX**: BPM-synced delay (with tap tempo and ping-pong) and reverb (size, damping, pre-delay, width, mix)
- **VOICES**: scale-aware harmonies (3rd, 5th, both, 3rd down, octaves) and a stereo doubler
- **MIX**: vocal/beat levels and a sync control for headphone latency
- **A/B** between the tuned mix and the original
- **Presets**: 7 built in, plus save your own
- **Library** of takes (rename, delete, reopen)
- **Export** WAV or M4A (AAC) to `Music/Totune`, or share straight to other apps

## Building
Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases** as `Totune.apk`.

Locally: open the folder in Android Studio, or run `./gradlew assembleRelease`.

The signing key (`app/totune.keystore`) is committed so each build installs over the previous one. Keep the repository private.
