# MiniCarHome

An easy-to-use, clutter-free home launcher for cars with Android "AI boxes", such as the
[**MMB MAX CarPlay AI Box for BMW 2023+ (iDrive 8/8.5/9), Android 13, 8+128GB**](https://www.amazon.com/dp/B0GXK6XD3N).
That's the box this was built and tested on, and it works with the **2023 MINI Cooper SE**.

### How the box works with the MINI

The 2023 MINI Cooper supports **wireless Apple CarPlay**, but not Android Auto. The box gets around that
by pretending to be an iPhone: it pairs with the car over Bluetooth, joins the car's CarPlay Wi-Fi, and
**streams its whole Android screen to the car over wireless CarPlay**. The car's touchscreen sends your
touches back. So you get a full Android system on the car's display, with no iPhone (or Android phone)
needed. With its own SIM card, the box also has its own mobile data.

Website: **https://minicarhome.kynrek.com** · Companion app: **[Box Tools](https://github.com/kynrek/BoxTools)**

Your navigation app runs live in a big window, and a slim panel beside it holds the clock, weather,
what's playing and the apps you actually use in the car. No ads, promo tiles or floating buttons.

It's currently configured for the **2023 MINI Cooper**'s 8.8-inch touchscreen, which the box drives
at **1180 × 480 pixels (120 dpi)**. Most of the layout adapts to the screen, but sizes and spacing were
tuned there. (The unit it was built on reports Android 14.)

![Home screen](docs/screenshots/home.png)
<sub>Home screen: Google Maps in the main window, the panel on the right with weather, now playing,
shortcuts and signal. (Account photo blurred.)</sub>

## Features

### A big main window for navigation (or any app)
Google Maps runs live in the main window, sized to fill everything the panel doesn't use, and it opens
by itself at startup. In settings you can pick any other app as the **main display app** (Waze, a music
app, anything).

### Apps open in the main window
Tap an app in the panel or the app list and it opens **in the main window**, where Maps was, with the
panel still beside it. Open another app and it replaces the previous one. **Home** brings Maps back, and
Maps keeps running underneath the whole time, so you don't lose your route. The first shortcut is always the
main display app's own icon, and tapping it does the same: it closes the app in the main window and brings
Maps (or Waze, or whichever app you chose) back. (Turn this off in settings to open apps full screen instead.)

![An app in the main window](docs/screenshots/pane.png)
<sub>The Play Store opened from the panel, running in the main window.</sub>

Android's own Settings app is the one exception: Android hides every app's overlay windows while it's
in front, so the panel disappears until you leave Settings.

### The panel
- **Clock and date** (12-hour with AM/PM, or 24-hour), with **weather** beside it (current conditions, high and low) when the panel is
  3 or more icons wide.
- **Now playing**: artwork, title, a progress bar and time remaining, and controls for whatever is
  playing (Podcast Addict, Audible, Spotify, Symfonium…): previous/next track (|◀ ▶|), seek back and
  forward (↺ ↻) and play/pause. Tap the card to open that app.
- **Shortcuts** to your car apps, scrolling if they don't fit, plus **All apps** and **Options** (settings).
- **Voice button** (off by default): say "navigate to In-N-Out" (or just "In-N-Out"). The panel shows what it
  heard while the map stays in view, and the route starts in Waze or Google Maps straight away, with no results
  list to tap through. Speech is recognized on the box. Or set it to open Android's assistant or an app you choose
  (Gemini, Home Assistant…).
- **Wi-Fi and cell signal** with bars and the network type (optional; off by default, since Android's status bar
  already shows them).

### Settings (Options tile)
| Setting | Options |
|---|---|
| Icons per row | 2–4; sets the panel's width (default 3). The main window grows into any space freed up |
| Side | Panel on the left or right |
| Display size (other apps) | Android's display density: 120, 140, 160 (default), 180 or 200 dpi. Makes Maps, the music player and other apps bigger or smaller; the panel keeps its size |
| Wallpaper | Choose a picture for the panel's background (shown with a dark tint so text stays readable), or None |
| Main display app | Any app; Google Maps by default |
| Open apps in main window | On / off |
| Clock & date, Media | Each on / off |
| 24-hour time | Off (12-hour with AM/PM, default) or on |
| Assistant | Off by default. When on: Built-in voice navigation (default), Android (the system assistant) or App (any app you pick) |
| Media buttons | Both (track + seek, default), Track only or Seek only |
| Seek seconds | 5, 10, 15 (default), 30 or 60 |
| Signal | Off (default), top or bottom |
| Edit shortcuts | Move (◀ ▶), remove (✕) and add (+, from the full app list) |
| Reset shortcuts | Back to the default list |

Everything is saved and kept across reboots.

<p>
<img src="docs/screenshots/settings.png" width="49%" alt="Settings">
<img src="docs/screenshots/edit.png" width="49%" alt="Editing shortcuts">
</p>
<sub>Left: settings. Right: editing shortcuts.</sub>

### Narrow or wide, left or right
The panel compacts itself as it narrows: shorter date, a smaller music card, stacked signal indicators.

<p>
<img src="docs/screenshots/two.png" width="49%" alt="Two icons per row">
<img src="docs/screenshots/left.png" width="49%" alt="Panel on the left">
</p>
<sub>Left: 2 icons per row. Right: panel on the left side, with settings open.</sub>

### App browser
**All apps** opens a browser in the main window, beside the panel, with search, a row of recently used apps
and every app A–Z. Tap an app and it opens there.

![All apps](docs/screenshots/browser.png)

### Box Tools (companion app)
[Box Tools](https://github.com/kynrek/BoxTools) is a separate repo. Install it alongside MiniCarHome:
- **Edge gestures** replace the box's floating ball: swipe in from the left or right edge for **Back**,
  swipe up from the bottom for **Home**, swipe up and pause for **Recent apps**.
- **Wireless Debug** icon: turns ADB-over-Wi-Fi back on after a reboot in one tap.
- **Stock Home** icon: opens the box's original launcher.
- Closes and reopens the main window's app when you change the panel width or side. Android doesn't let
  one app resize another app's open window.

## Install

### 1. Turn on Developer options and Wireless debugging on the box

The setup is done from a computer with [ADB](https://developer.android.com/tools/releases/platform-tools)
(Android's command-line tool) over Wi-Fi, so first unlock Developer options on the box. On the MMB box
this took tapping the build number in **both** Settings apps:

1. Open the box's own **Settings** app, go to **About**, and tap the build number repeatedly until it
   says you're a developer.
2. Open **More settings** (stock Android Settings), go to **About**, and tap **Build number** repeatedly
   until it says "You are now a developer".
3. Back in **More settings**, search for **Developer options** and open it.
4. Turn on **USB debugging** and **Wireless debugging**. When asked "Allow wireless debugging on this
   network?", choose **Allow**.
5. Connect the box to the same Wi-Fi network as your computer, then from the computer run
   `adb connect <box IP>:5555` (the box's IP is shown on the Wireless debugging screen) and accept the
   "Allow debugging?" prompt on the box. If port 5555 doesn't answer, pair with the code shown under
   **Pair device with pairing code** using `adb pair <IP>:<port>`, then `adb connect` the IP and port shown
   at the top of the Wireless debugging screen.

The box turns Wireless debugging off whenever it reboots. After setup, the **Wireless Debug** icon from
Box Tools turns it back on in one tap.

**Do you need ADB just to make MiniCarHome the home screen?** No. Once it's installed, you can pick it in
**More settings → Apps → Default apps → Home app**. But MiniCarHome needs ADB for the rest of the setup:
the hidden-API exemption and Box Tools' permission to switch Wireless debugging can only be granted over
ADB. Without them, the main window and the Wireless Debug icon won't work. (Freeform windows, display over
other apps, notification access and the accessibility service can also be switched on in the box's
Settings menus, but ADB does it all at once.)

### 2. Install the apps and run the setup

MiniCarHome is in this repo, prebuilt (`minicarhome/apk/`) and as source. Get Box Tools from its own
repo, **[kynrek/BoxTools](https://github.com/kynrek/BoxTools)** (`apk/boxtools.apk`). Install
**Box Tools first**, because it defines the permission MiniCarHome uses, then run the one-time setup over ADB:

```sh
adb install boxtools.apk          # from github.com/kynrek/BoxTools
adb install minicarhome/apk/minicarhome.apk

# MiniCarHome
adb shell settings put global enable_freeform_support 1
adb shell settings put global hidden_api_blacklist_exemptions "Landroid/app/ActivityOptions;->setLaunchWindowingMode,Landroid/view/IWindowManager,Landroid/app/IActivityTaskManager,Landroid/app/TaskInfo,Landroid/window/"
adb shell appops set com.kynrek.minicarhome SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.kynrek.minicarhome/com.kynrek.minicarhome.MediaListener 0
adb shell pm grant com.kynrek.minicarhome android.permission.READ_PHONE_STATE
adb shell pm grant com.kynrek.minicarhome android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.kynrek.minicarhome android.permission.WRITE_SECURE_SETTINGS   # Display size setting
adb shell pm grant com.kynrek.minicarhome android.permission.RECORD_AUDIO            # voice navigation (or allow on first use)
adb shell settings put secure icon_blacklist clock          # optional: hide the status-bar clock
adb shell cmd package set-home-activity com.kynrek.minicarhome/.HomeActivity

# Box Tools
adb shell pm grant local.boxtools android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services local.boxtools/local.boxtools.NavGestureService
adb shell settings put secure accessibility_enabled 1
adb shell pm disable-user --user 0 com.applepie.floatingball   # optional: hide the vendor floating ball
```

### Optional: Shizuku

A few apps (Aladdin Connect, for one) open their main screen in a new window that Android won't let another app
resize. [Shizuku](https://github.com/RikkaApps/Shizuku) runs a small helper with adb-level access that can. Install
it, start it, and allow MiniCarHome when Shizuku asks:

```sh
adb install shizuku.apk          # from github.com/RikkaApps/Shizuku/releases
adb shell "$(adb shell pm path moe.shizuku.privileged.api | cut -d: -f2 | xargs dirname)/lib/arm64/libshizuku.so"
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS   # lets it start on boot
adb shell settings put global force_resizable_activities 1   # lets apps marked non-resizable (TeamViewer) use a window
```

Shizuku stops when the box reboots. Start it again from the Shizuku app (Start, via wireless debugging, which
needs a Wi-Fi network, such as the car's CarPlay Wi-Fi), or pair it once and turn on its start-on-boot option.

Back to the stock home screen:
`adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher`

## Troubleshooting

**Tip for the MMB BMW box (from its support instructions):** after connecting the box to a power
source, use your phone to search for the Bluetooth device named **"JoyeBox"**, then dial
**#135177**. The LED indicator light switches from steady white to steady red.

**The box keeps rebooting and ADB drops.** If the box can reach your car over Bluetooth and Wi-Fi, it
starts a wireless CarPlay session; when that link drops, the box's CarPlay service reboots Android,
which also turns Wireless debugging off. Tap **Wireless Debug** (Box Tools) to turn it back on.

**An app opens full screen instead of in the main window.** Android won't move a window that's already
open. Swipe the app away in Recents once and it will open in the main window from then on. Some apps (Aladdin
Connect, for one) open their main screen as a new task from a splash screen, which Android sizes to the whole
screen. With [Shizuku](#optional-shizuku) running, MiniCarHome moves whatever window an app ends up in into the
main window, caption-free like any other app; without it those apps open full screen.

**An app keeps a ▭ ✕ bar.** It's locked to portrait (Aladdin Connect, TeamViewer, US Mobile, APKPure and
Netflix's sign-in screens are). Without the bar Android squeezes such apps into a narrow portrait column, so
MiniCarHome checks for that (with Shizuku) and gives them a freeform window, which fills the main window but
keeps the bar.

## Build

No Gradle needed: just the Android SDK build tools and platform 36 in `~/Library/Android/sdk`, and a JDK 21.

```sh
JAVA_HOME=/path/to/jdk-21 zsh minicarhome/build.sh
```

This writes `minicarhome/out/minicarhome.apk`, signed with `~/.android/debug.keystore`. Build
[Box Tools](https://github.com/kynrek/BoxTools) with the same key: MiniCarHome uses its signature permission.

## How it works

- The main window is a **multi-window task with launch bounds**, which (unlike a freeform window) has no
  caption bar with ▭ and ✕. Android's window shell only reliably shows a new window in that mode if it starts
  out as freeform, so each app opens as a freeform window and is switched to multi-window a second later.
  This needs the developer setting `enable_freeform_support` and a hidden-API exemption for
  `ActivityOptions.setLaunchWindowingMode`.
- The panel and app browser are **overlay windows**. Since Android 14, Google Maps carries a full-screen
  input sink (touch-opaque activities) that swallows touches meant for the home screen underneath it.
- Android won't resize another app's open window, so on a width or side change MiniCarHome switches the old
  window back to freeform, asks Box Tools' accessibility service to tap its ✕, then reopens the app at the
  new size.
- The panel is drawn at a fixed scale (1.7× the box's original 120 dpi), so changing Android's display size or font
  size only resizes the other apps. Display size uses `IWindowManager.setForcedDisplayDensityForUser`, which
  needs `WRITE_SECURE_SETTINGS` and a hidden-API exemption, both set over ADB.
- A wallpaper is stored at `/sdcard/Android/data/com.kynrek.minicarhome/files/wallpaper.jpg`, so you can also
  `adb push` a picture there.
- Weather comes from [Open-Meteo](https://open-meteo.com/) (free, no key) for the box's location,
  rounded to about 1 km.
- Launcher icons that only bounce to another screen (Podcast Addict) are mapped to their real screen
  in `TRAMPOLINES`, because the bounce doesn't land inside a freeform window.

## License

[MIT](LICENSE). Icons are from Google's Material icons (Apache 2.0). Includes the Shizuku API (MIT), see
`minicarhome/libs/README.md`.

## Website

`site/` is the static page served at https://minicarhome.kynrek.com (nginx:alpine; `site/nginx.conf`).
