# Pocket Portal

See and use your Android phone inside a Meta Quest 3. The phone app shares its screen over
Wi-Fi. On the Quest you open a web page in the Quest browser, so nothing is installed on the headset.

- Picture: the phone sends JPEG frames over a WebSocket, up to 30 fps.
- Control: tap, long-press, swipe, scroll (thumbstick), Back / Home / Recents /
  Notifications / Quick settings, and typing into the focused text box.
- Security: the viewer needs a 6-digit PIN shown on the phone. It only works on
  your local network, on port 8080.

APKs: `..\PocketPortal-1.0.0-debug.apk` (phone) and `..\PocketPortal-Quest-1.0.0-debug.apk` (headset). Publishing to Google Play: see [PLAY_STORE.md](PLAY_STORE.md).
Needs Android 11 or newer.

## 1. Install on the phone (Samsung Galaxy)

1. Copy `PocketPortal-1.0.0-debug.apk` to the phone (USB, Google Drive, Quick Share…)
   and open it. If asked, allow your file manager or browser to install unknown apps.
   - Or with USB debugging on: `adb install PocketPortal-1.0.0-debug.apk`
2. Open **Pocket Portal**.

## 2. Switch on remote touch (once)

1. In Pocket Portal tap **Open accessibility settings**.
2. Go to **Installed apps → Pocket Portal remote touch**, switch it **On**, then confirm.
3. **If the switch is greyed out** ("Restricted setting"), Android is blocking a
   sideloaded app. Go back to Pocket Portal, tap **Open App info**, tap **⋮** (top right),
   choose **Allow restricted settings**, and then switch the service on again.

Without this step the headset can still watch, but it can't control the phone.

## 3. Share the screen

1. Phone and Quest on the **same Wi-Fi**.
2. In Pocket Portal pick a quality (Balanced is fine), then tap **Start sharing**. Allow
   notifications and choose **Share entire screen** (the only option).
3. Note the address (e.g. `http://192.168.1.23:8080`) and the **PIN**.

## 4. Open it on the Quest 3

1. Open the **Browser** app on the Quest and type the address from the phone.
   - Tip: add `#PIN` to skip the PIN prompt, e.g. `http://192.168.1.23:8080/#482913`,
     then bookmark it. The address can change if your router hands out a new one.
2. Enter the PIN. The phone screen appears. You can resize or curve the browser
   window, or tap **Full screen**.

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
