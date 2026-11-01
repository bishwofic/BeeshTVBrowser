# BeeshTV Browser

A web browser made for the TV remote. The page fills the screen and a pointer moves over it with the
arrow keys: OK clicks, pushing past an edge scrolls. A bar drops down for the address, voice search,
ad blocking, desktop sites and zoom.

Built on the TV's own Android System WebView (Chromium), so pages render like they do in Chrome. It
pairs with [BeeshTV Launcher](https://github.com/bishwofic/BeeshTVLauncher) but works on its own.

## Features

- **A pointer for the D-pad.** It starts slow for precise aim and speeds up while you hold a
  direction. OK clicks (hold for a long press), hovering opens menus, and pushing the pointer against
  any edge scrolls whatever is under it, inner panes included. Page Up / Down and Channel Up / Down
  scroll a screen at a time.
- **One box for everything.** Type a web address to open it, or anything else to search Google,
  DuckDuckGo, Bing or Brave. The microphone button searches by voice.
- **Ad and tracker blocking**, on by default. It starts with a built-in list and switches to
  [Steven Black's hosts list](https://github.com/StevenBlack/hosts) (about 80,000 ad and malware hosts),
  refreshed weekly. The bar shows how many requests were blocked on the page.
- **Desktop site** per site, or for every site, when the phone version of a site is too cut down.
- **Zoom** per site, from 50% to 200%, remembered for next time.
- **Full-screen video** with Back to leave it.
- **Opens web links from other apps**: choose BeeshTV Browser and Always when the TV asks.
- **Updates itself** from this repo's GitHub releases, like BeeshTV Launcher.
- Small: about 300 KB, since the page engine is the TV's own.

## Using it

| Remote | |
|---|---|
| Arrows | Move the pointer (hold to go faster); push past an edge to scroll |
| OK | Click; hold for a long press |
| Menu, or Up at the top of a page | Show or hide the bar |
| Back | Previous page; on the first page, the bar |
| Page Up / Down, Channel Up / Down | Scroll a screen at a time |

In the bar, from the left: back, forward, reload, the address, voice search, ad blocking (with the
number blocked), desktop site, zoom out, zoom in, and settings. Down or Back hides it again.

**Settings** has the search engine, ad blocking and its list, desktop sites by default, clearing
cookies and history, and updates.

## Install

You need a computer with `adb` on the same Wi-Fi as the TV (`brew install android-platform-tools` on
a Mac). Works on Android TV and Google TV running Android 9 or newer.

1. On the TV, open **Settings → System → About** and press OK on **Android TV OS build** seven times.
   Go back to **Settings → System → Developer options** and turn on **USB debugging**.
2. Find the TV's IP address in **Settings → Network & Internet** (select your Wi-Fi network).
3. Download this repo (or just `dist/BeeshTV-Browser-1.0.apk` and `install.sh`) and run:

   ```bash
   ./install.sh 192.168.1.20
   ```

`install.sh` installs the APK, lets it install its own updates, and opens it. To remove it:
`./install.sh 192.168.1.20 --undo`.

Without a computer, download `BeeshTV-Browser-1.0.apk` from the
[latest release](https://github.com/bishwofic/BeeshTVBrowser/releases/latest), copy it to the TV (a USB
stick, LocalSend, or a downloader app) and open it.

## Updates

New versions are published as [GitHub releases](https://github.com/bishwofic/BeeshTVBrowser/releases).
The browser looks for one every six hours, downloads it in the background, and shows **Update** in
the bar when it's ready. The TV asks you to confirm before it installs.

To publish one: bump `android:versionCode` and `android:versionName` in `browser/AndroidManifest.xml`,
then run `./release.sh`, which builds the APK and publishes it as release `v<versionName>` with the
GitHub CLI. Keep `browser/debug.keystore`: an update signed with a different key won't install over
the old one.

## Build it yourself

No Gradle, just the SDK command-line tools:

```bash
brew install openjdk@17 android-commandlinetools
yes | JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager \
  --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-34" "build-tools;34.0.0"

browser/build.sh                 # builds dist/BeeshTV-Browser-<version>.apk
browser/build.sh 192.168.1.20    # builds and installs
```

The APK is signed with `browser/debug.keystore`, created on the first build (it isn't in the repo).

## Credits

- Page engine: the TV's Android System WebView (Chromium).
- Ad and tracker list: [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT).
- Typeface: [Schibsted Grotesk](https://github.com/schibsted/schibsted-grotesk) by Schibsted (SIL Open Font License).
- Icons are in the style of Google's Material Symbols (Apache 2.0).

Licences for bundled work are in `browser/licenses/`.

## License

MIT, see [LICENSE](LICENSE). Made by [Bishwo Bhusal](https://github.com/bishwofic).
