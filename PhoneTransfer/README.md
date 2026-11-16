# FTLink – PC ⇄ Phone chat & file transfer (any file type, both directions)

```
pc/ftlink_pc.py      Python app (GUI + server)         pip install aiohttp
android/             Android Studio project (Kotlin / Jetpack Compose)
.github/workflows/   builds the APK for you on GitHub (no Android Studio needed)
```

## 1. PC
```
pip install aiohttp
python pc/ftlink_pc.py          # add --headless for no window
```
The window shows **Address / Port / Token**. Allow Python through the firewall
(TCP 8765 and UDP 8766). Received files go to `~/FTLink/Received`.

## 2. Get the APK (pick one)
**A. GitHub (no installs):** push this folder to a GitHub repo → *Actions → Build APK* →
download the `FTLink-apk` artifact → copy `app-debug.apk` to the phone and install.

**B. Android Studio:** open the `android/` folder → *Build ▸ Build APK(s)*.

## 3. Connect
Phone and PC on the same Wi-Fi. Open the app → *Settings* → **Find PC** (or type the IP) →
enter the token → **Save & connect**. Then tap **Disable battery optimization**.

## Features
* Chat both ways; files of any type/size both ways (streamed, with progress, tap to retry)
* Files sent while the other side was offline arrive when it reconnects
* App runs as a foreground service: auto-reconnect, starts at boot
* Gallery tabs: Images / Videos / Files (long-press to delete); in-app image viewer
  (pinch-zoom) and video player; other types open in the matching app
* "Share → FTLink" from any Android app sends files/text to the PC

Security: LAN-only, plain HTTP protected by the 6-digit token. Don't expose port 8765 to the internet.
