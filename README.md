# Pocket Portal

See and use your Android phone inside a Meta Quest 3. The phone app shares its screen and
sound over Wi-Fi. On the Quest you use the Pocket Portal headset app, or open a web page in
the Quest browser.

- Picture: hardware H.265 (HEVC) at up to 120 fps, or H.264 when the headset can't decode
  H.265, decoded by the headset (WebCodecs). The bitrate backs off by itself when the Wi-Fi
  can't keep up. Browsers without WebCodecs get JPEG frames at up to 30 fps instead.
- Stats: the viewer shows codec, frame rate, bitrate and latency (capture on the phone to
  drawn in the headset) in the top-right corner; **⏱ Stats** hides it.
- Sound: the phone's media and game audio in stereo, optional. Phone calls can't be
  captured: Android blocks that for every app.
- Control: tap, long-press, swipe, scroll (thumbstick), Back / Home / Recents /
  Notifications / Quick settings, and typing into the focused text box. Hand tracking
  works too: pinch is a tap, pinch-and-hold a long press, pinch-and-drag a swipe.
- Security: everything stays on your local network and is encrypted (HTTPS and secure
  WebSocket on port 8443) with a certificate the phone creates in its keystore. The headset
  app pins that certificate, and the viewer also needs the 6-digit PIN shown on the phone.
  Port 8080 only redirects.
- Not supported: Netflix and other DRM apps block screen capture on purpose and show black.

APKs: `..\PocketPortal-1.0.0-debug.apk` (phone) and `..\PocketPortal-Quest-1.0.0-debug.apk` (headset). Publishing to Google Play: see [PLAY_STORE.md](PLAY_STORE.md).
Needs Android 11 or newer.

## 1. Install

1. Phone: copy `PocketPortal-1.0.0-debug.apk` to the phone and open it (allow installing
   unknown apps if asked), or `adb install PocketPortal-1.0.0-debug.apk`.
2. Quest: `adb install PocketPortal-Quest-1.0.0-debug.apk`. It appears under
   **Library → Unknown sources**.

## 2. Set up once

1. **Remote touch:** on the phone tap **Turn on**, then **Agree and continue**, and in
   Settings go to **Installed apps → Pocket Portal remote touch** and switch it **On**.
   - **If the switch is greyed out** ("Restricted setting"), Android is blocking a
     sideloaded app. In Pocket Portal open **More options → Open App info**, tap **⋮**,
     choose **Allow restricted settings**, then switch the service on again.
   - Without remote touch the headset can still watch, but it can't control the phone.

## 3. Connect

1. Phone and Quest on the **same Wi-Fi** (5 GHz or 6 GHz works best).
2. On the phone tap **Connect**. Allow notifications and choose **Share entire screen**.
3. On the Quest open **Pocket Portal** and pick the phone. The first time, enter the PIN
   shown on the phone; the headset remembers it. After that it reopens that phone by itself,
   also when the headset wakes up, as long as the phone is still sharing.

### Using the Quest browser instead

Under **More options** the phone shows its address (e.g. `https://192.168.1.23:8443`) and
the **PIN**. Type the address into the Quest **Browser**; it warns that the connection
isn't private because the phone signs its own certificate, so choose **Advanced → Proceed**.
Enter the PIN. Tip: add `#PIN` to the address to skip the prompt, then bookmark it.

## Phone screen while sharing

In Pocket Portal, under **Phone screen while sharing**:

- **Normal:** the phone shows its screen as usual.
- **Dim:** brightness drops to the minimum while sharing and comes back when you stop.
  Needs **Allow brightness control** once.
- **Off:** the phone's panel switches off completely while the headset keeps showing
  everything (like scrcpy). Needs a one-time setup through the phone's own Wireless
  debugging, with no PC and no other app:
  1. Turn on **Developer options** (Settings → About phone → Software information → tap
     **Build number** seven times).
  2. In Pocket Portal choose **Off**, tap **Set up screen-off** and allow notifications.
     Wireless debugging opens; switch it on if it's off.
  3. Tap **Pair device with pairing code**. Leave that code on screen, pull down the
     notification shade and type the code into the **Set up screen-off** notification.
  4. "Screen-off is set up" appears. You can close Wireless debugging.

  It keeps working after a restart: Pocket Portal switches Wireless debugging back on by
  itself when it needs it. If it can't switch the screen off, it dims it instead. Press the
  power button twice to see the phone's screen again.

The phone stays awake while sharing, so the screen timeout can't end the session.

Controls in the headset:

| Do this | Phone gets |
|---|---|
| Click (trigger / pinch) | Tap |
| Hold still for half a second or longer | Long press |
| Click-and-drag | Swipe (played back when you let go) |
| Thumbstick up/down over the screen | Scroll |
| Right-click | Back |
| Toolbar buttons | Back, Home, Recents, Notifications, Quick settings |
| ⌨ Keyboard: type, press Enter | Text added to the focused text box. ⌫ deletes, ⏎ is Enter/Go |

Stop with **Stop sharing** in Pocket Portal, or with **Stop** in the notification.

## Quest app (instead of the browser)

`..\PocketPortal-Quest-1.0.0-debug.apk` is a native Horizon OS panel app (module `quest/`).
It lists phones that are sharing on the same Wi-Fi, so you don't type an address
(you can still type one). Tap a phone to open the viewer inside the app.

### Install on the Quest 3 (sideload, once)

1. **Turn on developer mode.** Create a free developer organization at
   developers.meta.com/horizon (My Apps → Create organization). Then, in the **Meta Horizon**
   app on your phone, go to **Devices → your headset → Headset settings →
   Developer mode** and switch it **On**. Restart the headset.
2. **Connect the Quest to the PC** with a USB-C cable. In the headset, accept
   **Allow USB debugging** (tick *Always allow from this computer*).
3. **Install** from the PC:
   ```bash
   "C:/Users/sampa/AppData/Local/Android/Sdk/platform-tools/adb" devices
   "C:/Users/sampa/AppData/Local/Android/Sdk/platform-tools/adb" install "C:/LocGit/android app/PocketPortal-Quest-1.0.0-debug.apk"
   ```
   If the phone is also plugged in, add `-s <Quest serial>` (from `adb devices`).
   Or use SideQuest on the PC and drag the APK onto it.
4. **Open it:** in the headset, open **Library**, choose **Unknown sources** in the
   filter at the top right, and start **Pocket Portal**. Pin it to the dock for quick access.

### Use

1. On the phone, tap **Start sharing** in Pocket Portal.
2. On the Quest, open Pocket Portal. Your phone appears under **Phones nearby**. Tap it.
3. Enter the PIN once. It's remembered for that phone.
4. **‹ Phones** goes back, and **Reload** reconnects. You can resize the panel like any window.

## Limits

- Swipes are sent when you release, so dragging isn't live (scrolling and swiping work fine).
- Banking, DRM video (Netflix etc.) and password screens show black. Android blocks capturing them.
- No audio. Phone sound still plays from the phone.
- Typing works in normal text boxes, not in password fields or games.
- If it lags, choose **Fast** before starting (it can't be changed while sharing),
  or move closer to the router (5 GHz Wi-Fi is best).

## Building

The toolchain matches Tunely (AGP 9.4, Gradle 9.8.1, compileSdk 37, Android Studio's JBR):

```bash
cd "C:/LocGit/android app/Mirrorly"
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

This builds both `app/` (phone) and `quest/` (headset). The phone advertises
`_pocketportal._tcp` over mDNS while sharing, and the Quest app browses for it (`PhoneFinder`).

Code: `MirrorService` (MediaProjection capture → JPEG), `MirrorServer` (HTTP +
WebSocket, PIN check, latest-frame flow control), `TouchService` (accessibility
gestures/keys/text), `assets/index.html` (headset viewer).
