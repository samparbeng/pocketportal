# Google Play store listing: Pocket Portal

Paste these into **Play Console → Grow users → Store presence → Main store listing**.
The character limits are Google's, and every field below is within them.

## App name (max 30)

```
Pocket Portal: Phone in VR
```
(26 characters. The launcher still shows just "Pocket Portal".)

## Short description (max 80)

```
See and use your phone inside your VR headset: tap, swipe and type over Wi-Fi.
```
(78 characters)

## Full description (max 4000)

```
Put your phone inside your VR headset.

Pocket Portal shows your Android phone's screen as a window in your headset and lets you control it without taking the headset off. Reply to a message, check a notification, skip a song or look something up, then get straight back to what you were doing.

HOW IT WORKS
1. Open Pocket Portal on your phone and tap Start sharing.
2. On your headset, open the Pocket Portal headset app and pick your phone, or type the address shown on the phone into the headset's web browser.
3. Enter the PIN once. Your phone appears in front of you.

CONTROL YOUR PHONE FROM VR
• Point and click to tap, hold to long-press, drag to swipe
• Scroll with the thumbstick
• Back, Home, Recents, Notifications and Quick settings buttons
• Type with the headset's keyboard straight into the phone's text boxes
• Resize and move the phone window like any other panel

PRIVATE BY DESIGN
• Your screen travels only between your phone and your own headset, over your Wi-Fi
• No accounts, no cloud servers, no ads, no tracking
• A 6-digit PIN keeps others on your network out
• Nothing is recorded or saved

BUILT FOR SPEED
• Low-latency streaming at up to 30 frames per second
• Sharp, Balanced and Fast quality presets to suit your Wi-Fi
• Works in portrait and landscape

ACCESSIBILITY SERVICE DISCLOSURE
Remote touch is optional and uses Android's Accessibility Service API. With your permission, Pocket Portal uses it only to perform the taps, swipes and navigation actions you make in the headset, and to add the text you type in the headset to the text box you're typing into. Pocket Portal doesn't collect, store or share any of this information. Without remote touch, Pocket Portal works as a view-only mirror.

GOOD TO KNOW
• Your phone and headset must be on the same Wi-Fi network
• Requires Android 11 or newer
• Apps that block screen capture (some banking and video apps) appear black
• Sound keeps playing from the phone

Works with Meta Quest 3, Meta Quest 3S and Meta Quest Pro through the Pocket Portal headset app, and with any headset that has a web browser. Pocket Portal is an independent app and isn't affiliated with or endorsed by Meta or Samsung.
```

## Category and tags

- **App category:** Tools (alternative: Productivity)
- **Tags:** Remote control, Screen mirroring, Virtual reality

## Contact details

- **Email:** kojoamparbeng@gmail.com
- **Website:** https://samparbeng.github.io/pocketportal/
- **Privacy policy URL:** https://samparbeng.github.io/pocketportal/privacy/

## Graphics

| Asset | File | Requirement |
|---|---|---|
| App icon | `store/graphics/icon-512.png` | 512×512 PNG, 32-bit |
| Feature graphic | `store/graphics/feature-graphic-1024x500.png` | 1024×500 PNG/JPEG |
| Phone screenshots | `store/screenshots/phone-*.png` | 2–8 images, 320–3840 px, 9:16 or 16:9 |

To re-render the icon and feature graphic after editing the SVGs in `store/src/`:
`npm i @resvg/resvg-js` in any folder, then `node store/src/render.mjs <that folder>`.
