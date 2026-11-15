# Pocket Portal

See and use your Android phone inside a Meta Quest 3. The phone app shares its screen and
sound over Wi-Fi. On the Quest you use the Pocket Portal headset app, or open a web page in
the Quest browser. On a Samsung phone it can also open a full **DeX desktop** in the headset.

- Picture: hardware H.265 (HEVC) at up to 120 fps, or H.264 when the headset can't decode
  H.265, decoded by the headset (WebCodecs). The bitrate backs off by itself when the Wi-Fi
  can't keep up. Browsers without WebCodecs get JPEG frames at up to 30 fps instead.
- Sound: the phone's media and game audio in stereo, optional. Phone calls can't be
  captured: Android blocks that for every app.
- Control: tap, long-press, swipe, scroll (thumbstick), Back / Home / Recents /
  Notifications / Quick settings, and typing into the focused text box. Hand tracking
  works too: pinch is a tap, pinch-and-hold a long press, pinch-and-drag a swipe.
- Samsung DeX: a desktop on its own hidden display, in a wide window in the headset, with
  a mouse pointer, right-click menus and wheel scrolling. The phone's own screen is untouched.
- Notifications: the phone's notifications pop up in the headset, where you can reply to
  messages, open them or dismiss them. Battery level and low-battery warnings show too.
- Stats: the viewer shows codec, frame rate, bitrate and latency (capture on the phone to
  drawn in the headset) in the top-right corner; **⏱ Stats** hides it.
- Troubleshoot: a section on the phone checks Wi-Fi, delay, permissions and setup, with a
  fix button where there is one.
- Security: everything stays on your local network and is encrypted (HTTPS and secure
  WebSocket on port 8443) with a certificate the phone creates in its keystore. The headset
  app pins that certificate, and the viewer also needs the 6-digit PIN shown on the phone.
  Port 8080 only redirects.
- Not supported: Netflix and other DRM apps block screen capture on purpose and show black.

APKs: `..\PocketPortal-1.0.0-debug.apk` (phone) and `..\PocketPortal-Quest-1.0.0-debug.apk`
(headset). Publishing to Google Play: see [PLAY_STORE.md](PLAY_STORE.md).
Needs Android 11 or newer.

## 1. Install

1. Phone: copy `PocketPortal-1.0.0-debug.apk` to the phone and open it (allow installing
   unknown apps if asked), or `adb install PocketPortal-1.0.0-debug.apk`.
2. Quest: `adb install PocketPortal-Quest-1.0.0-debug.apk`. It appears under
   **Library → Unknown sources**. Step-by-step: [Install on the Quest 3](#install-on-the-quest-3-sideload-once).

## 2. Set up once

1. **Remote touch:** on the phone tap **Turn on**, then **Agree and continue**, and in
   Settings go to **Installed apps → Pocket Portal remote touch** and switch it **On**.
   - **If the switch is greyed out** ("Restricted setting"), Android is blocking a
     sideloaded app. In Pocket Portal open **More options → Open App info**, tap **⋮**,
     choose **Allow restricted settings**, then switch the service on again.
   - Without remote touch the headset can still watch, but it can't control the phone.
   - Once the [Wireless debugging setup](#phone-screen-while-sharing) is done, touch goes in
     through it while sharing instead. That also works on Google sign-in and Android
     permission screens, which ignore remote touch.
2. **Optional, recommended:** the Wireless debugging setup under
   [Phone screen while sharing](#phone-screen-while-sharing). Screen-off, DeX and the
   one-tap notification access all need it.

## 3. Connect

1. Phone and Quest on the **same Wi-Fi** (5 GHz or 6 GHz works best), or join the Quest to
   the phone's hotspot.
2. On the phone tap **Connect**. Allow notifications and choose **Entire screen**.
3. On the Quest open **Pocket Portal** and pick the phone under **Phones nearby**. The first
   time, enter the PIN and compare the security code with **More options** on the phone.
   Once the phone accepts the PIN, the headset remembers the pairing. It can reopen the
   phone after the headset wakes or the phone's Wi-Fi address changes, as long as it can
   discover the phone and the phone is still sharing.

Stop with **Disconnect** in Pocket Portal, or with **Stop** in the notification.

If something doesn't work, open **Troubleshoot** on the phone (see [Troubleshoot](#troubleshoot)).

### Using the Quest browser instead

Under **More options** the phone shows its address (e.g. `https://192.168.1.23:8443`) and
the **PIN**. Type the address into the Quest **Browser**; it warns that the connection
isn't private because the phone signs its own certificate, so choose **Advanced → Proceed**.
Enter the PIN. Tip: add `#PIN` to the address to skip the prompt, then bookmark it.

## Controls in the headset

| Do this | Phone gets |
|---|---|
| Click (trigger / pinch) | Tap |
| Hold still for half a second or longer | Long press |
| Click-and-drag | Swipe (played back when you let go) |
| Thumbstick up/down over the screen | Scroll |
| Right-click | Back |
| Toolbar buttons | Back, Home, Recents, Notifications, Quick settings |
| ⌨ Keyboard: type, press Enter | Text added to the focused text box. ⌫ deletes, ⏎ is Enter/Go |

In DeX the controls work like a mouse instead; see [Samsung DeX](#samsung-dex).

## Phone screen while sharing

In Pocket Portal, under **More options → Phone screen while sharing**:

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

## Samsung DeX

Under **More options → The headset shows**, pick **DeX desktop** before connecting. It needs
the Wireless debugging setup above.

When you connect, a DeX desktop starts on a separate, hidden display (1920×1080) and the
headset app opens it in a wide window. Nothing changes on the phone's own screen. When you
disconnect, apps you opened in DeX move back to the phone, and the headset goes back to its
tall phone window.

Controls in DeX:

| Do this | DeX gets |
|---|---|
| Point at the desktop | Mouse pointer (hover effects and taskbar previews work) |
| Click (trigger / pinch) | Left click; two quick clicks double-click |
| Hold still for half a second, then let go | Right-click (a blue ring fills while you hold) |
| A real mouse's right button | Right-click |
| Click-and-drag | Drag windows, select text (live, not played back) |
| Thumbstick up/down | Mouse wheel |
| Toolbar buttons | Back, Home, Recents, Notifications |

With hands instead of controllers, DeX works like a touchscreen: pinch is a tap, pinch-and-drag
swipes and scrolls, and pinch-and-hold is a long press (which opens DeX's right-click menu).
The pointer still follows your hand.

## Notifications and battery in the headset

Under **More options → Notifications in the headset** (on by default). It needs
notification access: tap **Allow notification access**. With the Wireless debugging setup
done, Pocket Portal allows it itself; otherwise Android's settings page opens (if the switch
is greyed out, use **Allow restricted settings** as for remote touch).

While you're connected, new notifications pop up at the top right of the headset view with
the app's icon, title and text:

- **↩ Reply** for messaging apps: type and press Enter or **Send**.
- **Open on phone** (or **Open in DeX**) opens it where you're looking.
- **✕** dismisses it on the phone too.

Cards fade after about 9 seconds, but stay while the pointer is on them or you're typing a
reply. At most 3 show at once. Ongoing notifications (music, navigation), silent ones and
anything Do Not Disturb would hide are skipped. **🔔 Alerts** in the toolbar hides the cards
in the headset; the switch on the phone stops them completely.

The phone's battery level shows at the top left. A card pops up at 20%, 10% and 5%, and
when the charger is plugged in or out.

Notifications only go to a headset that connected with your PIN, over the encrypted link.

## Troubleshoot

Tap **Troubleshoot** on the phone's main screen. It refreshes every couple of seconds and
lists problems first, each with a fix button where there is one:

- **Wi-Fi:** band (2.4, 5 or 6 GHz), signal strength and link speed. 2.4 GHz or a weak
  signal is the most common cause of lag. Using the phone's hotspot counts as fine.
- **Headset connection:** while connected, the delay and frame rate the headset measures,
  and whether the Wi-Fi is carrying the full picture quality. While waiting, tips for when
  the Quest can't find the phone (guest networks and some mesh routers keep devices apart).
- **Remote touch, permissions, Wireless debugging setup and notification access.**
- **Battery saver and heat:** both slow the phone and make the picture stutter.

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
   "C:/Users/sampa/AppData/Local/Android/Sdk/platform-tools/adb" install -r "C:/LocGit/android app/PocketPortal-Quest-1.0.0-debug.apk"
   ```
   If the phone is also plugged in, add `-s <Quest serial>` (from `adb devices`).
   Or use SideQuest on the PC and drag the APK onto it.
4. **Open it:** in the headset, open **Library**, choose **Unknown sources** in the
   filter at the top right, and start **Pocket Portal**. Pin it to the dock for quick access.

### Use

1. On the phone, tap **Connect** in Pocket Portal.
2. On the Quest, open Pocket Portal. Your phone appears under **Phones nearby**. Tap it.
3. Enter the PIN and confirm the security code matches the one shown under **More options**
   on the phone. A new phone certificate, including after a reinstall, requires pairing again.
4. **‹ Phones** goes back, and **Reload** reconnects. You can resize the panel like any window.
   For DeX the app opens a separate wide window by itself.

## Limits

- On the phone screen, swipes are sent when you let go, so dragging isn't live (scrolling
  and swiping work fine). DeX drags are live.
- Banking, DRM video (Netflix etc.) and password screens show black. Android blocks capturing them.
- Phone calls can't be streamed; their sound stays on the phone.
- Typing works in normal text boxes, not in password fields or games.
- Picture quality, sound, screen mode and DeX can't be changed while sharing: disconnect first.
- If it lags, open **Troubleshoot**. The usual fixes: 5 GHz Wi-Fi, closer to the router, or
  **Fast** picture quality.

## Building

The toolchain matches Tunely (AGP 9.4, Gradle 9.8.1, compileSdk 37, Android Studio's JBR):

```bash
cd "C:/LocGit/android app/Mirrorly"
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

This builds both `app/` (phone) and `quest/` (headset). The phone advertises
`_pocketportal._tcp` over mDNS while sharing, and the Quest app browses for it (`PhoneFinder`).

Code, phone side (`app/`):

- `MirrorService`: MediaProjection capture into the H.265/H.264 encoder (`VideoEncoder`) or
  JPEG, adaptive bitrate, sound (`AudioStreamer`), battery, and routing headset input.
- `MirrorServer`: HTTPS + secure WebSocket, PIN check, per-viewer flow control.
- `AdbBridge`: the phone's own Wireless debugging connection. It runs `PanelPower`
  (screen-off), `InputInjector` (shell touch, keys and DeX mouse) and `DexDisplay` (the DeX
  display and its encoder, read by `DexSession`) as the shell user.
- `TouchService`: accessibility gestures, keys and text when the shell route isn't set up.
- `HeadsetNotifications`: notification listener for headset toasts and quick reply.
- `Troubleshooter`: the checks behind the Troubleshoot section.
- `assets/index.html`: the viewer page, used by both the Quest app and the browser.

Headset side (`quest/`): `MainActivity` (phone list and tall viewer), `WideViewerActivity`
(the wide DeX window), `PhoneFinder` (mDNS).
