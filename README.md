# Totune

Record your voice, tune it and mix it on your Android phone (Android 10+, optimised for Pixel).

## What's new in 2.0
- **Real-time native engine** (C++ / AAudio low-latency): hear the autotune live in your headphones while recording (toggle on/off), and every change while editing plays instantly — no processing wait.
- **Three tuning styles**: **Tuny** (steady, locked hard tune), **Crisp** (snappy flips, brighter), **Wavy** (smooth, looser, formant-preserving spectral engine).
- **Rack**: the chain is a list of modules you add with **+ Add processing**, reorder by dragging **≡** (or tap ≡ for Move / Duplicate / Bypass / Remove), switch off with the power dot, and open full controls with **⚙**. Starts with just the Autotune; the same module can be added twice.
- New **reverb** (8-line feedback delay network, no metallic ringing).
- **Presets save the whole rack** (modules, order and settings).

## Modules
Autotune · Harmony · Vocoder · Doubler · EQ · Compressor · Grit · Chorus · Delay · Reverb

## Building
Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases** as `Totune.apk`.
