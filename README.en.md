<div align="center">

# Lumen

**An Apple TV–style home screen for China-market TVs.**

Turn on the TV and the episode you didn't finish is right there: a full-screen cover, one Resume button, and a row of your apps below.<br>
No ads, no recommendation feed. Tested feature by feature on a China-market TCL TV.

[中文](README.md) · **English**

![Lumen Home](docs/screenshots/home.jpg)

</div>

## Features

### Continue watching

- **Full-screen cover**: what you were last watching fills the screen, with only its title, progress and a Resume button on the left.
- **One-press resume**: Resume opens that episode at that moment, not the app's front page. The small icon in the button shows which app it comes from.
- **Newest first**: what you just watched is first when you come back. Anything watched for under 10 seconds, or finished, is left out.
- **Left / right**: on Resume, page through the last 6 titles.
- **Portrait posters**: for services that only give a portrait poster (Tencent Video), the poster fills the right half and fades into the background.

<p align="center">
  <img src="docs/screenshots/portrait.jpg" width="49%" alt="Portrait poster across the right half">
  <img src="docs/screenshots/all-apps.jpg" width="49%" alt="All Apps">
</p>

### App row

- Six tiles: by default **Bilibili, Tencent Video, iQIYI, Youku, Mango TV**, then All Apps.
- **Hold OK to reorder**; the order is kept.
- Every other app you installed follows those five; past six, the row scrolls sideways with the focus.
- TCL's own system apps are under All Apps. Nothing is uninstalled or disabled.

### Top bar and Control Center

- Top left: time and weather; the city follows the TV's system language. It is found from the network by default; in China IP location often lands on the provincial capital or a neighbouring city, so pick yours under Control Center → Weather city.
- Top right: network and settings. Select it for Control Center: inputs, network, system settings, volume, brightness.

<p align="center"><img src="docs/screenshots/control-center.jpg" width="60%" alt="Control Center"></p>

### Screensaver

- Apple TV aerials when the TV is idle, in place of TCL's own screensaver.
- **Home** or **Back** returns straight to Home.

## Which apps support Continue Watching

Lumen reads **the watch history TCL's own launcher keeps** (the history database of `com.tcl.cyberui`), which video apps write to as they play. So it works on China-market TCL TVs only.

| App | Package | Recorded | Resumes to episode and time | Cover |
|---|---|---|---|---|
| Bilibili (云视听小电视) | `com.xiaodianshi.tv.yst` | ✅ tested | ✅ tested | landscape |
| Tencent Video (云视听极光) | `com.ktcp.csvideo` | ✅ tested | ✅ tested | portrait only |
| iQIYI (奇异果 TV) | `com.tcl.qiyiguo` | ✅ tested | ✅ tested | landscape |
| Youku (CIBN 酷喵) | `com.cibn.tv` | not verified | not verified | — |
| Mango TV | `com.hunantv.market` | not verified | not verified | — |

With no history, Home shows the wallpaper and the app row.

## Install (China-market TCL TVs)

You need a computer (macOS or Linux) on the same Wi-Fi as the TV, with `adb` and `python3`.

```bash
brew install android-platform-tools   # macOS; on Linux install android-tools or adb
```

### Step 1: turn on ADB debugging on the TV

TCL / FFALCON keeps the ADB switch in a hidden menu:

1. Turn the TV's **volume down to 0**.
2. Open **Settings → About → Device info** (设置 → 关于 → 本机信息).
3. On that page, press **Up, Down, Left, Right** on the remote, **quickly** (under a second between presses).
4. The page reloads with two new groups, "商场" and "更多" (More). Turn on **ADB** under **More**.
   Careful: after the reload, focus sits on the store-mode (商场模式) switch. **Do not press OK** there; move off it first.
5. Note the TV's IP address under **Settings → Network**, e.g. `192.168.1.12`.

> Some guides say ADB switches itself off after a reboot. On a TCL 65V8L it stayed on; if yours turns it off, repeat the steps above.

The first time you connect, the TV asks "Allow USB debugging?". Choose **Allow** (you can tick "Always allow").

### Step 2: install and take over Home

```bash
git clone https://github.com/SiaBuilt/lumen-tv.git
cd lumen-tv
TV=192.168.1.12 tools/install.sh guard
```

- TCL refuses every app installed over ADB. The script pushes the APK through the TV's own **多屏互动** (Multi-Screen Interaction) instead. A prompt appears at the top right of the TV: **press Right, then OK, to choose 同意 (Agree)**.
- The script then grants what Lumen needs, allows it to start at boot, turns on Lumen Home Guard (so Home comes back to Lumen) and sets the aerial screensaver.
- Press **Home** on the remote: you are on Lumen.

To install your own build: `BUILD=1 TV=192.168.1.12 tools/install.sh guard` (needs JDK 17 and the Android SDK).

### Updating

Lumen checks for a new version every few hours. When there is one, it installs it in the background once the TV is idle (as the screensaver starts), through TCL's 多屏互动, with Home Guard agreeing for you and no installer screens. Or choose "Update to Lumen x" in Control Center to update now:

1. Select it; Lumen downloads the new version (with progress).
2. When the system installer opens, Lumen Home Guard presses Update for you (TCL's installer shows no focus on its button; pressing OK works too).

No computer needed. The version and APK are published both at `lumen.siabuilt.com` (Cloudflare, reachable from China) and on GitHub Releases; the former is tried first. Updating keeps your app order and settings.

You can also update from a computer, the way you installed: `git pull && TV=192.168.1.12 tools/install.sh guard`.

### Restore

```bash
TV=192.168.1.12 tools/restore.sh              # give Home back to TCL's launcher, keep Lumen
TV=192.168.1.12 tools/restore.sh --uninstall  # and remove Lumen
```

Lumen never uninstalls or disables TCL's system apps; after a restore the TV is as it was.

### Troubleshooting

- **Home goes to TCL's launcher**: `TV=192.168.1.12 tools/install.sh repair` restarts Home Guard.
- **No 多屏互动 prompt**: make sure the TV is not in its screensaver (press a key first), then run the install again.
- **What you just watched is not first**: give Home 2–3 seconds; some apps write their history only as they stop.

## Known limitations

- When a video is opened from Bilibili's own History page, Bilibili sometimes does not write it to TCL's watch history (or writes another video), so Continue Watching does not update. Opened from Lumen's Resume, it works.
- Continue Watching for Youku and Mango TV is not verified.

## Other makers

- **Install and Home takeover**: inherited from Aerial; tested on Xiaomi and TCL, with specific handling for Hisense, Coocaa and LeTV (see [docs/devices](docs/devices)). Non-TCL TVs usually install with `tools/install.sh` (without guard).
- **App row**: the five services ship under different packages per maker; the builds common on Xiaomi and others (云视听极光, 银河奇异果, 芒果 TV) are docked first too.
- **Continue watching**: built on the watch history of TCL's own launcher, so TCL only for now. Other makers keep no history an ordinary app can read; Home shows the wallpaper there.

## Tested TVs

| Model | System | Install | Home takeover | Start at boot | Wake from standby | Continue watching |
|---|---|---|---|---|---|---|
| TCL 65V8L (China, tc8000_ay30a2) | Android 11 | ✅ 多屏互动 | ✅ | ✅ | not tested | ✅ Bilibili / Tencent / iQIYI |

Other China-market TCL models should work the same way (same install and takeover); reports welcome.

## How it works

- **Home takeover**: TCL accepts only launchers it signed, so Lumen does not declare itself a launcher. An accessibility service, Lumen Home Guard, answers the Home key and brings Lumen back when TCL's launcher appears. It reads no screen content.
- **Continue watching**: Lumen reads the watch history in TCL's launcher database (readable by any app) and resumes a title the way TCL's launcher does. It reads no account or private data of the video apps.
- **Install**: TCL's install verifier refuses every ADB install but accepts apps pushed through 多屏互动. Protocol: [docs/tcast-protocol.md](docs/tcast-protocol.md); more TCL detail: [docs/devices/tcl-mt5879-cn.md](docs/devices/tcl-mt5879-cn.md).

## Development

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleGuardRelease
```

To release: raise `versionCode` and `versionName` in `app/build.gradle.kts`, put the version in `version.txt`, build, publish a GitHub release with `lumen-guard.apk`, then run `tools/publish-update.sh` to mirror it to lumen.siabuilt.com. TVs offer the update within a few hours (or when Control Center opens).

Kotlin and Jetpack Compose, laid out on a 960×540 dp canvas. Code structure: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits and license

Lumen is built on [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) (Aerial): Home Guard, the screensaver, and the TCL install and boot work all come from Aerial. Thanks to its author.

[MIT](LICENSE). The Inter font is under the [SIL OFL](FONT-LICENSE-OFL.txt). App icons, covers and titles belong to their owners.
