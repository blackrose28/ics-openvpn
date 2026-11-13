# Testing ics-openvpn with docker-android

Setup guide for running the app in an Android emulator inside Docker, using
[budtmo/docker-android](https://github.com/budtmo/docker-android). No host
emulator or Android Studio install needed — only Docker with KVM.

## Verified prerequisites (this machine)

- Docker daemon accessible (user in `docker` group, no sudo needed)
- `/dev/kvm` present and writable (user in `kvm` group) → hardware-accelerated emulator
- APK already built: `main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk`

## Resource constraints

- **Disk**: keep ~10 GB free before pulling; the image is ~3 GB compressed, more unpacked
- **RAM**: emulator needs ~2 GB; close memory-heavy apps on the host while testing

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
  budtmo/docker-android:emulator_9.0
```

- Port `6080` → noVNC: interact with the emulator screen in a browser at <http://localhost:6080>
- Port `5555` → ADB reachable from the host
- `--device /dev/kvm` → boots at usable speed

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
  First connection triggers the system **VPN permission dialog** — click it in the
  noVNC window (it cannot be granted by a plain adb command).
- **Profiles**: push a config for import:
  ```bash
  docker exec android-test adb push your.ovpn /sdcard/Download/
  ```
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

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Disk fills during image pull | Free space first (Step 1); use the smallest viable tag |
| ~2 GB RAM → emulator sluggish or OOM | Close host memory hogs; keep the noVNC tab lightweight |
| First-run boot takes 5–15 min | Poll `sys.boot_completed`; do not restart the container early |
| VPN permission dialog blocks automated tests | Grant it once manually via noVNC per fresh install |
