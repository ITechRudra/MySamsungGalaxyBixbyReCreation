# Bixi: your offline voice agent for Samsung Galaxy

Bixi is a floating robot assistant that controls your phone when **you** tell it to, by voice or by typing.
It opens apps, scrolls and likes reels, shares, comments, messages and calls people, flips phone settings,
and taps or types on any screen. It works **fully offline**: no AI service, no API key, and no internet
permission at all.

<p align="center"><i>Tap the robot → say "open Instagram reels" → "keep scrolling and liking" → "stop".</i></p>

---

## Install it on your phone (no PC needed)

1. Open this repository's **Releases** page on your phone and download **`Bixi.apk`** from **"Bixi: latest build"**.
   (It's rebuilt automatically by GitHub Actions on every push.)
2. Open the downloaded file. When asked, allow **Install unknown apps** for your browser / My Files.
3. Open **Bixi** and follow the **setup checklist** on the Home tab:
   - **Accessibility (required).** This is Bixi's "hands". In *Accessibility → Installed apps → Bixi*, switch it on.
     > **Switch greyed out?** Android blocks Accessibility for apps installed from a file until you allow it:
     > *Settings → Apps → Bixi → ⋮ (top-right) → **Allow restricted settings***, then try again.
   - **Microphone (required)** to talk to Bixi.
   - Optional: **Contacts** ("call Mom"), **Phone** (direct calls), **Modify system settings** (brightness),
     **Do not disturb access**, **Battery: unrestricted** (stops One UI putting Bixi to sleep).
4. The robot now floats over every app. **Tap** it to talk, **long-press** it to type, **drag** it to move.

### Faster ways to call Bixi
- **Side key:** *Settings → Advanced features → Side button → Double press → Open app → **Bixi Talk***.
- **Quick Settings tile:** pull down the panel, tap ✎ and add the **Bixi** tile.
- **Assistant gesture:** *Settings → Apps → Choose default apps → Digital assistant app → **Bixi***, then swipe up from a bottom corner.
- **Bluetooth headset** button long-press also opens Bixi.

### Truly offline speech
Bixi asks Android's speech recogniser to stay on-device. To make that work without internet, download the pack once:
*Settings → General management → Keyboard list and default → Google voice typing → Offline speech recognition → English (India)*.
If the pack is missing, Bixi automatically falls back to the normal recogniser.

---

## What you can say

Say "please", "Hey Bixi" or "for me" freely. Chain commands with **then** / **and**. Hinglish works too.

| Area | Examples |
|---|---|
| **Reels & videos** | "open reels" · "open YouTube shorts" · "next" · "skip 5 reels" · "previous" · "like" · "unlike" · "dislike" · "share" · "share this with Mom on WhatsApp" · "comment nice video" · "follow" · "save this reel" · "pause" · "mute" |
| **Hands-free scrolling** | "keep scrolling" · "auto scroll every 5 seconds" · "keep scrolling and liking" · "stop" (or tap the robot) |
| **Apps** | "open WhatsApp" · "open YONO SBI" · "open WhatsApp status" · "go back" · "go home" · "recent apps" · "close all apps" · "uninstall Candy Crush" |
| **People** | "call Mom" · "video call Rahul" · "WhatsApp call Papa" · "send good morning to Mom on WhatsApp" · "message Priya saying I'm on my way" · "send an SMS to Dad saying call me" · "open chat with Amit" |
| **Money** | "open Google Pay" · "pay 500 to rahul@okaxis" · "send 200 rupees to Mom on GPay". Bixi opens the payment screen and **you** enter the PIN. |
| **Phone controls** | "turn on WiFi" · "Bluetooth off" · "flashlight" · "turn on do not disturb" · "dark mode on" · "volume up" · "set volume to 40" · "brightness 70 percent" · "take a screenshot" · "lock the phone" |
| **Anything on screen** | "tap Login" · "press OK" · "skip ad" · "tap the first result" · "open the second chat" · "type Hello there" · "send" · "scroll down" · "scroll to the top" · "find Settings" · "read the screen" · "read my notifications" · "copy" · "paste" |
| **Search, maps, media, clock** | "search cute cats on YouTube" · "search iPhone 16 on Amazon" · "google weather in Delhi" · "play Arijit Singh on Spotify" · "next song" · "navigate to India Gate" · "pizza near me" · "set an alarm for 6:30 am" · "set a timer for 10 minutes" · "remind me to drink water in 20 minutes" · "take a selfie" |
| **Hinglish** | "WhatsApp kholo" · "agli reel" · "like karo" · "Mummy ko call karo" · "Papa ko good night bhejo" · "awaaz badhao" · "torch jalao" · "screenshot lo" · "ruko" |
| **Your routines** | Add phrases in the **Routines** tab, or say "when I say good night then turn on DND and lock the screen". |
| **Bixi** | "help" · "again" (repeat last command) · "what time is it" · "battery level" · "hide the robot" |

---

## Safety: "everything on my command"

- Bixi **never acts on its own**. Every action starts from something you said, typed or tapped.
- Before **messaging, calling, commenting, sharing to someone or paying**, Bixi asks *"Should I do this?"*.
  You can answer by voice ("yes" / "haan" / "no") or by tapping. You can turn this off in Settings.
- It **never types into password or PIN boxes** and never presses the final Pay button. You always enter UPI PINs.
- There is **no internet permission** in the app, so nothing you say or see can leave the phone through Bixi.
- Some banking apps deliberately hide their screens from every assistant (and some refuse to run while any
  accessibility service is on). Bixi can still open them, but it can't tap inside them.

---

## How it works

```
 voice / typing ──► CommandParser (offline rules, English + Hinglish) ──► Agent ──► Executor
                                                                       │            ├─ Accessibility: read screen, tap, swipe, type
   floating robot ◄── AgentBus (mood, messages) ◄──────────────────────┘            └─ Android intents: apps, calls, WhatsApp, alarms, maps…
```

| Path | What's there |
|---|---|
| `app/src/main/java/com/bixi/agent/nlu/` | The offline brain: `CommandParser` (all the rules), `Normalizer` (speech quirks, Hinglish), `TimeParser`, `Fuzzy` |
| `.../exec/Executor.kt` | How each command is carried out (like-button finding, WhatsApp flow, Quick Settings toggles, …) |
| `.../core/Agent.kt` | Runs commands, confirmations, auto-scroll |
| `.../service/` | `BixiAccessibilityService` (the hands), `Screen` (gestures and screen reading), `FloatingBubble` (the overlay robot), Quick Settings tile |
| `.../ui/robot/BixiRobot.kt` | The animated mascot: idle / listening / thinking / speaking / moving (+ happy, confused, sleeping) |
| `.../ui/` | `VoicePanelActivity` (the listening sheet), `MainActivity` + `screens/` (Home, Commands, Routines, Settings) |
| `app/src/test/.../CommandParserTest.kt` | ~200 example phrases the parser must understand |

**Add your own command:** add a regex rule in `CommandParser.single()`, an `Action` type if needed, handle it
in `Executor.run()`, and add a test line in `CommandParserTest`.

---

## Build from source

Requirements: JDK 17+, Android SDK (platform 35). Then:

```bash
./gradlew testDebugUnitTest    # parser tests
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

### Seamless updates (optional but recommended)
Android only installs an update over the old app if both are signed with the same key. The key is **not**
stored in this public repo. Without it, each GitHub build gets a one-off key, so you must uninstall Bixi
before installing a newer build (and switch Accessibility on again). To fix that once:

```bash
keytool -genkeypair -keystore bixi.jks -alias bixi -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Bixi"
base64 -w0 bixi.jks > bixi.jks.b64
```

Then on GitHub: *Settings → Secrets and variables → Actions → New repository secret*:
- `BIXI_KEYSTORE_BASE64` = contents of `bixi.jks.b64`
- `BIXI_KEYSTORE_PASSWORD` = the password you chose

Keep `bixi.jks` private. Never commit it.

---

*Bixi is an independent hobby project. It isn't affiliated with or endorsed by Samsung. "Bixby" and "Galaxy" are trademarks of Samsung Electronics.*
