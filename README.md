# MYRAA AI – voice phone assistant (Android 8 → 16)

Live voice (Gemini Live API), sees your screen, controls any app through an Accessibility service, keeps running in the background.

## Build the APK without a computer setup (GitHub)
1. Create a free GitHub account → New repository → upload ALL files of this folder (including the hidden `.github` folder).
2. Open the **Actions** tab → "Build APK" → Run workflow (or it runs on every push).
3. When it turns green, open the run → **Artifacts → MYRAA-apk** → download, unzip, install `app-debug.apk`.
(Or open the folder in Android Studio and press Run.)

## First run
1. Open the app → paste your free key from https://aistudio.google.com/apikey
2. Tap every ⚠️ row and allow it. For **Accessibility** on Android 13+: App info → ⋮ → *Allow restricted settings* → then enable MYRAA in Accessibility.
3. Tap **START MYRAA** and talk. Say things like "open WhatsApp and message Rina that I'm late".

## Notes
- If the connection closes at once, the status shows the reason. Most common: wrong key, or the model name changed → edit "Live model name" (see https://ai.google.dev/gemini-api/docs/models).
- Screen: Android 11+ uses the Accessibility screenshot (no prompt). Android 8–10: press START, then "Allow screen capture".
- Some phone brands (Xiaomi, Oppo, Vivo, Samsung) also need "Autostart"/"Unrestricted battery" in system settings to keep it alive.
- Use headphones for the best echo-free conversation.
