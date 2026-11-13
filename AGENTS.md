# AGENTS.md

Working notes for coding agents operating in this repository.

Scope: how to run and test this app on the Docker Android emulator on **this
machine**, plus repo facts an agent needs. Human-facing setup guide:
`doc/docker-android-test-setup.md` (keep it in sync when you learn something new).

## Environment facts (verified 2026-11)

| Fact | Value |
|---|---|
| Docker daemon | works, but **the login session has stale groups** → prefix every call with `sg docker -c "..."` |
| `sudo -n` | passwordless, needed only for `systemctl restart docker` |
| `/dev/kvm` | present, `root:kvm`, works with `--privileged --device /dev/kvm` |
| RAM / disk | 7 GB total (~4 GB available) / 42 GB free after prune |
| Host `adb` | **not installed** — always use the container's adb: `docker exec android-test adb ...` |
| Emulator image | `budtmo/docker-android:emulator_9.0` (API 28) |
| Container | `android-test`, ports 6080 (noVNC) + 5555 (adb) |
| First boot | **~9 min** (measured 540 s to `sys.boot_completed=1`) |

Quoting rule for the wrapper: the whole docker command goes in double quotes,
inner strings in single quotes:

```bash
sg docker -c "docker exec android-test adb shell 'getprop sys.boot_completed'"
```

## Repo facts

- Package: `de.blinkt.openvpn` (flavors `ui`/`skeleton` × `ovpn23`/`ovpn2`, no
  `applicationIdSuffix`). `minSdk 23`, `compileSdk/targetSdk 37`.
- Test APK: `main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk`
  (build dir is gitignored — a fresh clone has none; build it first).
- Relevant activities: `de.blinkt.openvpn.activities.MainActivity`,
  `de.blinkt.openvpn.LaunchVPN` (credentials prompt),
  `de.blinkt.openvpn.activities.ConfigConverter{,File}` (import).
- The repo ships **no `.ovpn` files**; a profile must come from the user. Never
  invent credentials, and do not paste them into commits or docs.

## Golden path

Start every session by checking state — do not recreate what already exists.

```bash
sg docker -c "docker ps -a --filter name=android-test --format '{{.Names}} {{.Status}}'"
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:6080/vnc.html   # 200 = usable
sg docker -c "docker exec android-test adb shell dumpsys window | grep -m1 mCurrentFocus"
```

Create only if missing:

```bash
sg docker -c 'docker run -d --name android-test --privileged --device /dev/kvm \
  -p 6080:6080 -p 5555:5555 \
  -e EMULATOR_DEVICE="Samsung Galaxy S10" \
  -e WEB_VNC=true -e WEB_VNC_PORT=6080 \
  budtmo/docker-android:emulator_9.0'
```

Wait for boot by polling, never by restarting:

```bash
sg docker -c "docker exec android-test adb shell getprop sys.boot_completed"   # loop until "1"
```

Install / reinstall:

```bash
sg docker -c "docker cp main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-release.apk android-test:/tmp/"
sg docker -c "docker exec android-test adb install -r /tmp/main-ui-ovpn23-release.apk"
sg docker -c "docker exec android-test adb shell monkey -p de.blinkt.openvpn 1"
```

Import a profile (order matters — see traps):

```bash
sg docker -c "docker cp /path/to/x.ovpn android-test:/tmp/x.ovpn"
sg docker -c "docker exec android-test adb push /tmp/x.ovpn /sdcard/Download/x.ovpn"
sg docker -c "docker exec android-test adb shell pm grant de.blinkt.openvpn android.permission.READ_EXTERNAL_STORAGE"
sg docker -c "docker exec android-test adb shell am start \
  -n de.blinkt.openvpn/.activities.ConfigConverter \
  -a android.intent.action.VIEW \
  -d 'file:///sdcard/Download/x.ovpn' -t 'application/x-openvpn-profile'"
# tap the save FAB (fab_save), then the profile appears in PROFILES
```

## UI driving without a human

`adb` + `uiautomator` is enough for most flows; prefer it over guessing pixels.

```bash
# dump the view tree (resource ids + text + bounds)
sg docker -c "docker exec android-test adb shell 'uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml'" \
  | grep -oE 'text="[^"]+"|resource-id="de[^"]+"'

# bounds of one node, e.g. the save FAB
... | grep -o 'resource-id="de.blinkt.openvpn:id/fab_save"[^>]*bounds="[^"]*"'
# -> bounds="[1152,2752][1376,2976]"  tap the centre: 1264 2864
sg docker -c "docker exec android-test adb shell input tap 1264 2864"

# confirm which window is focused (cheap, reliable assertion)
sg docker -c "docker exec android-test adb shell dumpsys window | grep -m1 mCurrentFocus"

# screenshots for the user
sg docker -c "docker exec android-test adb exec-out screencap -p" > /tmp/opencode/shot.png
```

The system VPN consent dialog **can** be dismissed by coordinate tap (find
`text="OK"` bounds in the dump). Granting it once per install is enough.

## Traps that cost time

| Symptom | Cause | Fix |
|---|---|---|
| `permission denied ... docker.sock` even though `id` lists `docker` | stale supplementary groups in the current session | `sg docker -c "..."`; re-login is not guaranteed to help |
| `iptables: No chain/target/match by that name` on `docker run` | Docker's `DOCKER` filter chains missing | `sudo -n systemctl restart docker` recreates them (safe when no containers run) |
| Port 6080 closed, `gave up: vnc_web entered FATAL state` | current images need `WEB_VNC`/`WEB_VNC_PORT` | pass both env vars on `docker run`. To avoid a 9-min reboot, start it live: `docker exec -d android-test sh -c 'export WEB_VNC=true WEB_VNC_PORT=6080; /usr/local/bin/docker-android start vnc_web > /tmp/vncweb.log 2>&1'` |
| `am start -a VIEW` opens a chooser | app has two matching import activities | add `-n de.blinkt.openvpn/.activities.ConfigConverter` |
| Import lands on a permission dialog | storage read not granted | `pm grant ... READ_EXTERNAL_STORAGE` (the app does not declare `WRITE_EXTERNAL_STORAGE`; that grant errors, harmless) |
| `pm grant` fails with "has not requested permission" | permission not declared for that SDK | expected for `WRITE_EXTERNAL_STORAGE`; don't chase it |
| `FATAL EXCEPTION ... com.android.systemui ... TunerServiceImpl` | known SystemUI bug in this image's API 28 | ignore; assert on `mCurrentFocus` instead |
| `adb: command not found` on host | platform-tools absent | use `docker exec android-test adb` |
| Emulator sluggish / OOM | ~2 GB emulator on 7 GB host | close host memory hogs; avoid parallel heavy builds |

## Escalate to the human for

- **Credentials.** Profiles with `auth-user-pass` stop at
  `de.blinkt.openvpn.LaunchVPN` ("Need Password"). Ask the user to type in
  noVNC, or explicitly offer adb injection knowing it exposes the secret in the
  transcript.
- **Deleting the container** (`docker rm -f android-test`): it costs ~9 min to
  rebuild state. Ask first, and say why.
- Judgement calls about whether a VPN behaviour is a product bug vs. a test
  profile limitation.

## Iteration loop

Rebuild → `docker cp` → `adb install -r` → relaunch → assert → capture.

```bash
sg docker -c "docker logs --tail 30 android-test"                                  # supervisor/emulator
sg docker -c "docker exec android-test adb logcat -d -b crash | tail -40"         # app crashes
sg docker -c "docker exec android-test adb logcat -d | grep -i blinkt | tail -40" # app logs
```

Prefer `logcat -d` (dump) over a streaming `logcat` pipe — a stream blocks the
command forever.

Stop the emulator only when finished testing; otherwise leave it running for the
next session.
