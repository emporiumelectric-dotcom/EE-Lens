# FanLens Android prototype

A zero-cloud native Android prototype for recognizing catalogue products in a live camera view.

## What works

- Camera permission and live CameraX preview
- On-device image-embedding recognition with no cloud connection
- Six bundled fan products trained from the supplied shop-display photos
- First-test mode recognizes one fan at a time inside the centre guide
- A floating product name card appears only after a stable match
- Tap a name for a compact product detail sheet
- Tap outside or press Back to close the sheet and return to recognition

## Run

Open this folder in Android Studio, let it sync, then run the `app` configuration on an Android 8.0+ device. The project uses JDK 17, Android SDK 37, MediaPipe running locally, and no paid or cloud API.

## Next architecture step

After validating the six-product test, add a dedicated fan detector so several products can be located and recognized simultaneously.
