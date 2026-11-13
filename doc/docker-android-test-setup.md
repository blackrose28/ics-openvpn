# Testing ics-openvpn with docker-android

Setup guide for running the app in an Android emulator inside Docker, using
[budtmo/docker-android](https://github.com/budtmo/docker-android). No host
emulator or Android Studio install needed — only Docker with KVM.

For agent-oriented runbook notes (exact command wrappers, automation recipes,
and traps), see [`AGENTS.md`](../AGENTS.md).

## Verified prerequisites (this machine)

- Docker daemon accessible (user in `docker` group, no sudo needed)
- `/dev/kvm` present and writable (user in `kvm` group) → hardware-accelerated emulator
- JDK **21** (full JDK, not JRE): `sudo apt install openjdk-21-jdk-headless`.
  A JRE-only install fails with `Toolchain installation ... does not provide the
  required capabilities: [JAVA_COMPILER]`.
- Android SDK at `/data/Android/Sdk` with `platforms;android-37.0`,
  `build-tools;37.0.0`, `cmake;3.22.1`, `ndk;30.0.14904198`
- APK built (see Step 0): `main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk`

## Resource constraints

- **Disk**: keep ~10 GB free before pulling; the image is ~3 GB compressed, more unpacked
- **RAM**: emulator needs ~2 GB; close memory-heavy apps on the host while testing

## Step 0 — Build the APK

A fresh clone has no APK and uninitialized submodules. One-time setup:

```bash
# 1. native sources (openssl, openvpn2/3, mbedtls, fmt, lz4, asio)
git submodule update --init --recursive

# 2. SDK packages — needs cmdline-tools; API 37 is spelled "android-37.0"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/data/Android/Sdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-37.0" "build-tools;37.0.0" "cmake;3.22.1" "ndk;30.0.14904198"

# 3. point Gradle at the SDK (local.properties is gitignored)
printf 'sdk.dir=/data/Android/Sdk\n' > local.properties

# 4. make Gradle use JDK 21 even though the login shell exports JAVA_HOME=11
printf 'org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64\n' > ~/.gradle/gradle.properties
```

Then build. Release needs signing keys, so sign with the debug key for local
testing (the `icsopenvpnDebugSign` property is supported by the build script):

```bash
./gradlew :main:assembleUiOvpn23Release -PicsopenvpnDebugSign
```

Output: `main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk`
(~39 MB universal APK with `x86_64`/`x86`/`arm64-v8a`/`armeabi-v7a` native
libraries, so it runs on the emulator).

## Step 1 — Free disk space

```bash
docker system prune          # unused images/containers
rm -rf ~/.gradle/caches/*    # or move to a bigger disk (see below)
df -h /                      # re-check
```

## Step 2 — Pull the image

```bash
docker pull budtmo/docker-android:emulator_9.0
```

Tag choices (compressed size, all 2026-11 versions):

| Tag | API | Size | Notes |
|---|---|---|---|
| `emulator_9.0` | 28 | ~2.9 GB | smallest, recommended default |
| `emulator_11.0` | 30 | ~3.3 GB | modern always-on-VPN / scoped-storage behavior |
| `emulator_13.0` | 33 | ~3.5 GB | most realistic for current users |
| `emulator_14.0` | 34 | ~3.4 GB | latest |

## Step 3 — Run the container

```bash
docker run -d --name android-test \
  --privileged --device /dev/kvm \
  -p 6080:6080 -p 5555:5555 \
  -e EMULATOR_DEVICE="Samsung Galaxy S10" \
  -e WEB_VNC=true -e WEB_VNC_PORT=6080 \
  budtmo/docker-android:emulator_9.0
```

- Port `6080` → noVNC: interact with the emulator screen in a browser at <http://localhost:6080/vnc.html>
- Port `5555` → ADB reachable from the host
- `--device /dev/kvm` → boots at usable speed
- `WEB_VNC=true` + `WEB_VNC_PORT=6080` are **required** on current images (2026-11):
  the `vnc_web` service exits immediately with
  `env WEB_VNC cannot be found, VNC_WEB is not started!` without them, so port 6080
  stays closed even though the emulator itself is fine.

### NoVNC from another device (Tailscale)

`-p 6080:6080` binds **all** host interfaces, so noVNC is already reachable on the
host's Tailscale IP — no `tailscale serve`/Funnel required (verified 2026-11):

```text
http://100.86.207.15:6080/vnc.html?autoconnect=true&resize=scale
http://chuonglvubuntu.tailf87e57.ts.net:6080/vnc.html?autoconnect=true&resize=scale
```

`vnc.html` ships without autoconnect, so pass `?autoconnect=true&resize=scale` or
click **Connect**. ADB is published the same way: `adb connect 100.86.207.15:5555`.

Caveats:

- **No authentication.** `x11vnc` runs with `-nopw`, and the 0.0.0.0 bind means the
  LAN IP (`http://<host-lan-ip>:6080`) can drive the emulator too. To restrict it
  to the tailnet, recreate the container with `-p 100.86.207.15:6080:6080`
  (recreate ≈ 9 min, so confirm first), or add a tailnet ACL for port 6080.
- **Clipboard needs a secure context**: `navigator.clipboard` is unavailable over
  plain `http://`. `tailscale serve` is currently *not enabled* on this tailnet;
  enable Serve in the admin console, then
  `sudo tailscale serve --bg --https=443 http://127.0.0.1:6080` →
  `https://chuonglvubuntu.tailf87e57.ts.net/vnc.html`. That proxies host loopback,
  so the container itself needs no change.

## Step 4 — Wait for boot & verify

First boot takes 5–15 minutes. Poll, don't restart early:

```bash
docker exec android-test adb shell getprop sys.boot_completed   # wait for "1"
adb connect localhost:5555 && adb devices                      # host adb; otherwise use the container's adb
```

## Step 5 — Install the app

```bash
docker cp main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk android-test:/tmp/
docker exec android-test adb install -r /tmp/main-ui-ovpn23-release.apk
docker exec android-test adb shell monkey -p de.blinkt.openvpn 1   # launch
```

## Step 6 — Test flows

- **Manual/UI**: browse to <http://localhost:6080>, tap through import/connect.
  First connection triggers the system **VPN permission dialog**. There is no
  `pm grant` for it, but a coordinate tap on its `OK` button from a
  `uiautomator dump` works, so it can be automated — see `AGENTS.md`.
- **Profiles**: push a config for import:
  ```bash
  docker exec android-test adb push your.ovpn /sdcard/Download/
  ```
  Import it with an explicit component (the app has two matching activities, so
  a bare `VIEW` intent opens a chooser), after granting storage read:
  ```bash
  docker exec android-test adb shell pm grant de.blinkt.openvpn android.permission.READ_EXTERNAL_STORAGE
  docker exec android-test adb shell am start \
    -n de.blinkt.openvpn/.activities.ConfigConverter \
    -a android.intent.action.VIEW \
    -d 'file:///sdcard/Download/your.ovpn' -t 'application/x-openvpn-profile'
  ```
  Profiles using `auth-user-pass` then prompt for credentials in the app; those
  cannot be supplied non-interactively without exposing them in the transcript.
- **Logs/crashes**:
  ```bash
  docker exec android-test adb logcat | grep -i blinkt
  ```

## Step 7 — Iterate & cleanup

```bash
# after rebuilding the APK
docker cp main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk android-test:/tmp/
docker exec android-test adb install -r /tmp/main-ui-ovpn23-release.apk

# when done
docker rm -f android-test
docker rmi budtmo/docker-android:emulator_9.0
```

The container persists between iterations; remove it only when finished testing.

## Troubleshooting (observed on this machine, 2026-11)

| Symptom | Cause | Fix |
|---|---|---|
| `permission denied ... /var/run/docker.sock` although `id` shows the `docker` group | Login session predates the group change, so the shell has stale supplementary groups | `sg docker -c "docker ..."`, or log out/in (new shell does not always help) |
| `Unable to enable OPEN PORT rule ... iptables: No chain/target/match by that name` | Docker's `DOCKER` filter chains are missing (daemon started without them / flushed) | `sudo systemctl restart docker` recreates them; retry the `docker run` |
| Port 6080 closed, `docker logs` shows `gave up: vnc_web entered FATAL state` | Missing `WEB_VNC` / `WEB_VNC_PORT` env vars | Add them to `docker run`. To avoid a 9-minute reboot, start it live inside the running container: `docker exec -d android-test sh -c 'export WEB_VNC=true WEB_VNC_PORT=6080; /usr/local/bin/docker-android start vnc_web > /tmp/vncweb.log 2>&1'` |
| `FATAL EXCEPTION ... com.android.systemui ... TunerServiceImpl.clearAll` in `logcat -b crash` | Known SystemUI bug in this image's API 28 system | Ignore, it is not the app; check `dumpsys window` for `mCurrentFocus` to confirm the app is foreground |
| `adb: command not found` on the host | Android platform-tools not installed on this machine | Use the container's adb: `docker exec android-test adb ...` |

Measured first boot on this machine: ~9 minutes (`sys.boot_completed` flipped at 540 s).
`/dev/kvm` and `--privileged` both present, so the delay is disk/CPU bound, not emulation mode.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Disk fills during image pull | Free space first (Step 1); use the smallest viable tag |
| ~2 GB RAM → emulator sluggish or OOM | Close host memory hogs; keep the noVNC tab lightweight |
| First-run boot takes 5–15 min | Poll `sys.boot_completed`; do not restart the container early |
| VPN permission dialog blocks automated tests | Grant it once via a `uiautomator` coordinate tap, or manually via noVNC, per fresh install |
