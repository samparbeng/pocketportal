# Publishing Pocket Portal on Google Play

Everything the phone app (`app/`, package `app.pocketportal`) needs for Google Play,
in the order you'll do it. The headset app (`quest/`) isn't a Play Store app; it goes to
the Meta Horizon Store or is sideloaded.

| What | Where |
|---|---|
| Upload bundle | `app/build/outputs/bundle/release/app-release.aab` (build: see step 6) |
| Upload key | `keystore/upload.jks` + `keystore.properties` (**back these up**, see step 0) |
| Listing text | `store/listing.md` |
| Icon / feature graphic | `store/graphics/` |
| Screenshots | `store/screenshots/` |
| Privacy policy | `store/privacy-policy.html` (needs hosting, step 2) |

---

## 0. Back up the upload key (do this today)

`keystore/upload.jks` and `keystore.properties` (which holds its password) are
**not** in version control. Copy both to somewhere safe, like a password manager
or a private cloud folder. If you lose them, you can still ask Google to reset the
upload key, but that takes days.

## 1. Developer account

1. Sign up at play.google.com/console. It's a one-time US$25 fee, plus identity verification.
2. **New personal accounts must run a closed test first:** at least **12 testers**
   opted in for **14 days in a row** before you can apply for production. Line up
   friends or family with Android phones now (step 7).

## 2. Host the privacy policy

Google needs a public URL. The simplest free option is GitHub Pages:

1. Create a **public** GitHub repo, e.g. `pocketportal`.
2. Upload `store/privacy-policy.html` as `privacy/index.html`.
3. Repo **Settings → Pages →** deploy from the `main` branch.
4. The URL is `https://<your-username>.github.io/pocketportal/privacy/`.

Then put that URL in `app/src/main/res/values/strings.xml` (`privacy_policy_url`)
so the app shows a "Privacy policy" link, and rebuild. (Google Sites works too.)

## 3. Create the app in Play Console

**Create app →** App name `Pocket Portal: Phone in VR`, default language English (US),
**App**, **Free**, and accept the declarations.

## 4. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | Your hosted URL from step 2 |
| Ads | **No**, the app contains no ads |
| App access | **All functionality is available without special access.** Add this note for reviewers: *"No login. To test remote viewing without a headset: tap Start sharing, then open the address shown in the app (http://&lt;phone-ip&gt;:8080) in any web browser on the same Wi-Fi and enter the PIN shown in the app. Remote touch: tap 'Turn on remote touch', agree, enable 'Pocket Portal remote touch'."* |
| Content rating | Category **Utility, Productivity, Communication, or Other**. Answer **No** to every question (no violence, user-generated content sharing, location sharing, purchases or gambling). Expected rating: Everyone / PEGI 3. |
| Target audience | **18 and over** (keeps the app out of the Families program; it isn't made for children) |
| News app | No |
| Data safety | See below |
| Government app | No |
| Financial features | None |
| Health | None |

### Data safety form

Google counts data as "collected" when it's sent off the device to you (the
developer) or a third party. Pocket Portal sends the screen and touch input **only
to the user's own headset on their local network**. You never receive it and there's
no server. The accurate answers are:

- **Does your app collect or share any of the required user data types?** **No**
- Data encrypted in transit: *not asked when nothing is collected.* (The local stream is
  PIN-protected but not encrypted. Say so if a reviewer asks.)
- Account creation / deletion: the app has no accounts.

If a reviewer pushes back and insists the screen stream counts as collection, change it to
*Collected: Photos and videos → "Other" (screen images), App activity → "Other actions"*,
processed ephemerally, not shared, required for functionality, user can't opt out.

## 5. Sensitive permission declarations (Policy → App content)

These forms appear after the first upload. Each one asks for a **short video**: record the
phone screen with Android's screen recorder, upload it to YouTube as *Unlisted*, and paste the link.

### Accessibility API (`Pocket Portal remote touch`)

- **Is the app an accessibility tool?** **No** (the manifest declares `isAccessibilityTool="false"`)
- **Core functionality:** *Remote control of the user's own phone from their VR headset.
  The service performs only the taps, swipes and global actions (Back, Home, Recents,
  Notifications) the user makes in the headset viewer, and adds text typed in the headset
  to the focused text field. It does not collect, store or transmit screen content or input.*
- **Prominent disclosure:** the in-app "Allow remote touch?" dialog appears before the
  app opens Accessibility settings, and needs **Agree and continue**.
- **Video:** show tapping *Turn on remote touch*, the disclosure dialog, agreeing,
  enabling the service in Settings, then a tap made from the headset or browser viewer
  acting on the phone.

> Risk: Google reviews non-accessibility uses of this API strictly. If it's rejected,
> ship 1.0 as view-only by removing `TouchService` from the manifest, and appeal with the video.

### Foreground service: `mediaProjection`

- **Use case:** *Screen sharing to the user's VR headset, started by the user and stoppable
  from the notification.*
- **Video:** tap Start sharing, the system capture prompt, the ongoing notification, and
  the screen showing in the viewer.

## 6. Build the release bundle

Raise `versionCode` in `app/build.gradle.kts` for **every** upload (1, 2, 3…), then:

```bash
cd "C:/LocGit/android app/Mirrorly"
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab`. On the first upload, accept
**Play App Signing**: Google keeps the app signing key and you keep the upload key.

## 7. Testing tracks → production

1. **Internal testing:** upload the bundle, add your own Gmail, and install it from the opt-in link.
2. **Closed testing:** create a track, add at least 12 testers (an email list or Google Group),
   and keep it running for at least 14 days. Push updates there as you fix things.
3. **Apply for production** (Dashboard), answer the questions about your test, and roll out.

## 8. Store listing

Copy the fields from `store/listing.md`, then upload the icon, the feature graphic and at least
two screenshots from `store/`.

---

### What's already done in the app for policy compliance

- The Accessibility Service declares `isAccessibilityTool="false"` and has an accurate description.
- A prominent disclosure and consent dialog appears before sending the user to Accessibility settings.
- Screen capture uses the system consent prompt every time and shows an ongoing notification with Stop.
- `ACCESS_LOCAL_NETWORK` (Android 17+) is requested at the moment the user starts sharing.
- No ads, analytics, accounts or network calls beyond the user's own LAN.
- Backups are disabled (`allowBackup=false` + `data_extraction_rules.xml`).
- Release builds are shrunk with R8 and signed with the upload key; versioning starts at 1 / 1.0.0.
