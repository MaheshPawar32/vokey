# Vokey — real-device test checklist

APK: `release/vokey-0.1.0-device-test.apk` (signed release, Google **test** ads).
Write ✅ / ❌ and a note for each step. For any ❌, take a screenshot.

## 0. Install
1. Uninstall any older Vokey / VoiceMate build.
2. USB: `adb install release/vokey-0.1.0-device-test.apk`
   — or copy the APK to the phone, open it, allow “Install unknown apps” for your file manager.
3. Settings → Apps → Vokey → Battery → set **Unrestricted** (needed on Samsung/Xiaomi/OnePlus for background listening).

## 1. First launch
- [ ] Animated splash, then 3 onboarding slides (swipe + Next).
- [ ] “Set Up Vokey”: tap **Allow & Continue** → Vokey’s microphone notice → Android mic dialog → notification dialog → Home.

## 2. Finder — app open
- [ ] Tap **Start Finder** → “Listening…”, timer runs, level bars move when you talk.
- [ ] Status bar shows the green mic dot and “Vokey is listening” notification.
- [ ] Clap once loudly ~1–2 m away → phone rings, **Phone Found!** screen.
- [ ] **Stop Alert** stops sound, vibration (and flash if on).
- [ ] Wait 6 s, whistle steadily 1–2 s → Phone Found (Whistle detected).
- [ ] **Snooze (30s)** → clap within 30 s does nothing; after 30 s it works again.

## 3. Finder — app in background (most important)
- [ ] Start Finder → press **Home**.
- [ ] Clap or whistle → phone rings; “Phone found” notification with Stop alert / Snooze / Stop listening.
- [ ] **Screen off** (power button), wait 1 min, clap → rings? Note result.
- [ ] Phone in another room, screen off 5+ min, whistle → rings? Note distance.
- [ ] Notification **Stop** ends listening (mic dot disappears).

## 4. False alarms (leave Finder running 10 min each)
- [ ] Talking / phone call on another phone nearby
- [ ] Music or TV playing
- [ ] Door closing, typing, dishes
Note every unexpected ring and what caused it. Try **Clap → Double** pattern if single clap gives false alarms.

## 5. Alerts
- [ ] Alert Studio → **Preview alert**: volume starts low and rises over ~10 s.
- [ ] Sound Library → **Import sound** (MP3 from Downloads) → preview → use it → trigger by clap.
- [ ] Flashlight on (Alert Studio → Flashlight) → trigger → rear light blinks, turns off on Stop.
- [ ] Headphones plugged in, Output = **Auto** → where does the alert play? Output = **Speaker only** → speaker. Output = **Headphones only**, unplug → no sound, vibration only.

## 6. Voice (optional, needs Wi-Fi once)
- [ ] Voice Commands → Download offline model → enable.
- [ ] Airplane mode on → Start Finder → say “Find my phone” → rings.

## 7. Controls
- [ ] Quick Settings: add **Vokey** tile. Tap while off → opens Vokey. Tap while listening → stops.
- [ ] Home-screen widget: shows Listening + timer; **Stop** works.
- [ ] Monitor tab → **Stop everything** stops alert + listening.

## 8. Settings / data
- [ ] Battery Centre: Auto-stop at 15 % (needs low battery to test; optional).
- [ ] History shows detections; Analytics counts them.
- [ ] Settings → turn off “Share usage analytics” / “Send crash reports” (switches stay off after restart).

## 9. Play “foreground service” video (record while doing step 3)
Quick Settings → **Screen record** with **device audio + microphone** → Start Finder → pull down notification → Home → clap/whistle → rings → Stop in notification. Upload unlisted to YouTube, paste link in Play Console.

## Send back
Phone model + Android version, the ✅/❌ list, screenshots of failures. If possible connect by USB and run:
`adb logcat -d | grep -iE "Vokey|AndroidRuntime" > vokey_log.txt`
