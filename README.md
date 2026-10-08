# Maatram Hard Lock (Android, Kotlin)

Standalone focus-lock app. Start a Hard Lock for a set time; distracting apps
are blocked and the lock can't be cancelled — it ends on its own when the timer
runs out. Pure Kotlin + Jetpack Compose, no login, no server, works offline.

Website: https://maatram.co.in · Blog: [How to Stop Scrolling Reels: 5 Friction Tricks for Students](https://maatram.co.in/blog-stop-scrolling-reels.html)

## Version 2.0: the sakura

- **Your sakura grows while you focus.** The lock screen shows a seed that
  sprouts, grows and blooms as the timer runs (`SakuraArt.kt`).
- **Temptation drops leaves.** Each time you open a locked app the tree drops a
  leaf, and the screen shows which apps you tried.
- **My garden.** Every finished lock plants a tree: streak, total trees, and a
  4-week grid where empty squares show missed days (`Garden.kt`).
- **Real-time sky.** Sunrise, day, sunset and a starry night behind the tree,
  following the phone's clock.
- **Home-screen widget** (`PlantWidget.kt`): the growing tree with a live
  countdown during a lock, or your streak and last tree otherwise.

## How the lock holds (Accessibility + Device Admin)

- **Focus Shield** (an AccessibilityService) watches which app comes to the
  foreground. During a lock, a blocked app snaps straight to the home screen and
  a small "Locked" pill shows. Going home is instant — no lag.
- While locked, the **Settings app and the app-installer screens are also
  bounced**, so the shield can't be turned off, the app can't be force-stopped,
  and it can't be uninstalled.
- **Device Admin** (optional but recommended) makes Android refuse to uninstall
  the app until it's deactivated — and that screen is in Settings, which is
  guarded while locked.
- **The lock is time-based and survives reboots and crashes.** It always ends on
  its own (max 90 min).

Only the apps you pick are blocked. Nothing is ticked by default; the picker
sorts your apps by screen time (with Usage access) so the heavy ones are on top.
Every app you don't pick keeps working.

**Linked devices.** Type the link code from maatram.co.in (App Gate) under
*Link devices*: a Hard Lock started on the website, in the Chrome extension or
here locks all of them (`Link.kt`).

## Get the APK (GitHub Actions — no Android Studio)

1. Push this folder to a GitHub repo (branch `main`).
2. Open the **Actions** tab → the **Build APK** run → download the
   **maatram-hardlock-debug** artifact → install the APK on the phone.

**Signing key (do this once).** Without it every build is signed with a new
throwaway key, so a new APK won't install over the old one ("App not installed")
and you have to uninstall, losing your garden and settings. Add these repo
**Secrets** (Settings → Secrets and variables → Actions) and every build after
that uses the same key:

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

(`KEY_ALIAS` defaults to `maatram`; `KEY_PASSWORD` defaults to `KEYSTORE_PASSWORD`.)
Keep the keystore file and password safe: losing them means one more uninstall.

Make a keystore with:
```
keytool -genkey -v -keystore release.keystore -alias maatram \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # paste output into KEYSTORE_BASE64
```

## On the phone (one time)

1. Install the APK (allow install from unknown sources).
2. Android 13+: Settings → Apps → Maatram Hard Lock → ⋮ → **Allow restricted
   settings** (needed before accessibility can be turned on for a sideloaded app).
3. Open the app → tap **Turn on Shield** → enable *Maatram Focus Shield*.
4. Tap **Turn on protection** to add Device Admin (recommended).
5. Tap **Allow background running**. On Xiaomi/Redmi/POCO, also tap **Open
   Autostart settings** and turn Maatram Hard Lock on. Without this, "Clear all"
   in Recents kills the Shield and ends the lock early. Also open Recents, hold the
   Maatram Hard Lock card and tap the lock icon so "Clear all" skips it.
6. Tap **Choose apps to lock**, pick a duration and **Start Hard Lock**.

## Known limits (honest)

- **Safe mode** (reboot holding power) can turn the shield off — but blocked apps
  are also disabled in safe mode, so it takes two reboots to actually get around.
- A **hardware factory reset** from recovery wipes the phone and clears the lock.
  No app can stop that.
- Full lock-down that even safe mode can't touch needs **Device Owner** mode
  (adb setup) — not used here by choice.
