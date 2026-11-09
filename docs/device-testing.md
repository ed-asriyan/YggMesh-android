# On-device testing of the nearby mesh

A brief for a session (or a person) with ADB access to real phones. Tested on
a Pixel 10 (Android 17, LTE) and a Pixel 2 (Android 11, no network of its
own): the Pixel 2 reached the Yggdrasil network and loaded `.ygg` sites only
through the Pixel 10, over BLE and over Wi-Fi Aware.

## Working from the dev container (nothing installed on the host)

`.devcontainer/` has everything: JDK, Android SDK and NDK, Rust with the
Android targets, cargo-ndk, adb, and Claude Code. The host needs only Docker
and VS Code with the Dev Containers extension. Open the repository in VS Code
and choose "Reopen in Container"; the first build takes a while. Run `claude`
in the container terminal and sign in once (the login persists in a volume).

**USB phones do not reach the container.** Docker Desktop on a Mac does not
pass USB devices through; USB/IP can, but it needs a third-party server
installed on the host. Instead, the adb server runs on the host and the
container's adb is only a client of it (`ANDROID_ADB_SERVER_ADDRESS` in the
Dockerfile points it at `host.docker.internal`). Nothing is installed on the
host: `contrib/host-adb.sh` downloads Google's platform-tools into a temporary
directory, runs the server, and removes everything when stopped with Ctrl-C.

1. On the host, in a terminal, in the cloned repository: `./contrib/host-adb.sh`
   (needs only `sh`, `curl` and `unzip`). Leave it running while testing.
   Phones: USB debugging on, plugged in, "Allow USB debugging" accepted.
2. In the container: `adb devices -l` lists the phones; use `adb -s SERIAL`.

Notes:

- The script and the container use the same platform-tools version, from
  `ARG PLATFORM_TOOLS` in `.devcontainer/Dockerfile`; a client and a server of
  different versions fail. Change it there when updating.
- By default the server listens on all interfaces, which is what the article
  [Integrating Docker and adb](https://twosixtech.com/integrating-docker-and-adb/)
  found necessary for Docker Desktop. Anyone who can reach the port can run
  commands on the phones, so use a network you trust and stop the script when
  done. `LOCAL_ONLY=1 ./contrib/host-adb.sh` listens on localhost only; whether
  Docker Desktop for Mac reaches that was not verified, but it is the safer
  thing to try first.
- Wireless debugging (Android 11+) also works through the same server:
  `adb pair IP:PORT`, then `adb connect IP:PORT` in the container (mDNS
  discovery does not work, so always give `IP:PORT`).

## What the app does

- Yggdrasil-ng in `libs/yggdrasil-ng-jni`, built into `libyggdrasil.so` by
  `contrib/build-yggdrasil-ng.sh` (needs Rust, cargo-ndk, NDK r25+).
- `app/src/main/java/eu/neilalexander/yggdrasil/mesh/` finds nearby devices
  running the app and bridges each link into a loopback listener of the local
  node: `WifiAwareTransport` (Wi-Fi Aware data path, link-local IPv6),
  `BleTransport` (insecure L2CAP over BLE, no pairing), `NearbyMesh` (shared
  logic), `LinkBridge` (stream splice). Android 10+ only.
- Peers screen shows the status of each transport and the link counts.
- Package `me.asriyan.yggmesh`, label YggMesh.

## What the tests on those phones found

- BLE links come up in about 20 s. Dialing worked only from the Pixel 2 to the
  Pixel 10, so the higher node ID dials after 5 s too.
- Wi-Fi Aware between that pair is still not stable: links live from 10 s to
  about 3 min, and most setups fail. A BLE link stays up next to it. Open
  (unencrypted) data paths came up and carried nothing. Test on newer pairs too:
  how much of this is the Pixel 2 is not known.
- A Pixel 2 as the initiator never finished a setup (0 of 9), and did as the
  responder. The lower node ID initiates, which on that pair is the Pixel 2, so
  it may not link at all. A setup abandoned halfway can wedge its firmware until a
  reboot (status 9003 at once, Wi-Fi off and on does not help): when comparing
  builds, reboot it first, or the results mean nothing.
- On Android 11 an IPv4 address that is not the first of a VPN crashes the app
  when a data path comes up (see establishTun).
- BLE between that pair, with LE 2M: about 0.6 Mbit/s from the Pixel 10 and
  0.15 Mbit/s from the Pixel 2, with seconds of latency under load. The link
  starts on LE 1M; the GATT client in BleTransport moves it to 2M, which barely
  changed the speed. A third device linked over BLE cut it 3-5 times in early,
  rougher tests. IfMTU 1280 changed nothing between the phones and may help
  through an exit (0.31-0.36 against 0.27-0.28 Mbit/s, two runs each).
- While a Wi-Fi Aware data path was up, LTE on the Pixel 10 slowed down to
  1-4 s of latency.

Capture:

    adb -s <serial> logcat -s NearbyMesh MeshWifiAware MeshBle MeshLinkBridge Yggdrasil bt_l2cap bt_btif_sock

## Test plan

1. Per device: `adb shell getprop ro.product.model`, `ro.build.version.release`,
   `adb shell pm has-feature android.hardware.wifi.aware`.
2. Install the debug APK, grant the runtime permissions the app asks for
   (`pm grant` may refuse some; otherwise tap the dialogs: screenshot with
   `adb exec-out screencap -p`, tap with `input tap`), accept the VPN dialog.
3. Phone A online with one public peer (https://publicpeers.neilalexander.dev)
   in Peers. Phone B with no peers configured and "Search for multicast
   peers" off, so that its only way to the Yggdrasil network is the nearby
   link (it may be on the same Wi-Fi: the app's Yggdrasil traffic does not
   use that network).
4. Wi-Fi Aware link (both phones support it): B should list A as a peer;
   Peers shows "Links: 1 Wi-Fi Aware". Then B should reach the Yggdrasil
   network through A (ping a public node's 200::/7 address from B).
5. BLE link: the same, with Wi-Fi Aware off on one side or on a device
   without it.
6. Moving apart and back: peers return within about a minute, and a BLE link
   stays up next to a Wi-Fi Aware one to the same peer.

## Open questions the tests answer

- Do Wi-Fi Aware and a joined Wi-Fi network work together on the chip?
- Does closing a subscribe session tear down data paths made through it?
  (The code avoids it; not verified.)
- How long until Yggdrasil reroutes after a link drops?
- Does a new subscribe session report a peer an older one already saw?

## Rules

- The Wi-Fi password goes in an environment variable for the session, never
  in a committed file or in a command that is logged by the repository.
- Develop on branch `ccr-13c113c8-gr0b08`; do not open a pull request unless asked.
