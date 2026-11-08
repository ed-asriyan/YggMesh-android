# YggMesh for Android
Yggdrasil mesh between phones, with no Wi-Fi network and no Internet needed

> [!WARNING]
> This project is on extremely early stage. Something may break even if it's supposed to work. Do it on your own risk.

## The Idea
This app is the Android side of [YggMesh](https://github.com/ed-asriyan/YggMesh): a mesh network for cases where normal network infrastructure is unavailable or unreliable.

- The phone runs a [Yggdrasil](https://yggdrasil-network.github.io) node ([Yggdrasil-ng](https://github.com/Revertron/Yggdrasil-ng), its Rust implementation) as a VPN, so any app on it can reach Yggdrasil IPv6 addresses. Your usual traffic still goes through your provider.
- Phones running the app find each other and peer with no user interaction, over [Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware) and Bluetooth Low Energy. No shared Wi-Fi network, router or Internet is needed. Phones out of range of each other still reach each other through the ones in between.
- A phone with Internet (mobile data, Wi-Fi) also peers with public Yggdrasil nodes, and carries the Yggdrasil traffic of the phones around it.
- Planned: access to clearnet (the Internet) websites through the mesh, via [YggMesh exit nodes](https://github.com/ed-asriyan/YggMesh-exit-node), so that a phone with no Internet of its own reaches the Internet through any nearby phone that has it.
- It peers with [YggMesh routers](https://github.com/ed-asriyan/YggMesh) and any other Yggdrasil node too.

All traffic over Yggdrasil is encrypted end-to-end. It is not anonymous: direct peers see your IP address, and nearby phones see that your phone runs the app.

## Getting Started

### Requirements
- Android 10 or newer for nearby phones. The app runs on Android 5+, but then only connects to configured and multicast peers.
- Bluetooth LE for nearby phones over Bluetooth. Almost every phone has it.
- Wi-Fi Aware for faster links between nearby phones. Many phones have it (most Pixels, many Samsung and Xiaomi phones). Without it, the app uses Bluetooth only.

### Installing
Download `yggmesh.apk` from the [Releases](https://github.com/ed-asriyan/YggMesh-android/releases) page and open it on your phone. Android asks to allow installing apps from the browser or file manager you opened it with.

To get updates automatically, use [Obtainium](https://github.com/ImranR98/Obtainium): add the app by the URL of this repository, `https://github.com/ed-asriyan/YggMesh-android`, and Obtainium installs new releases as they come out.

### Configuring
1. Open YggMesh and turn on **Enable Yggdrasil**. Accept the VPN request.
2. Allow the permissions it asks for: nearby devices (or location on Android 10-12), which Wi-Fi Aware and Bluetooth need to find nearby phones.
3. Nothing to do for peers: a phone with Internet picks public peers by itself (see Automatic peers below), and a phone without Internet gets everything through the phones around it. To add a peer of your own, open **Peers** and tap **Add peer**.
4. **DNS servers**: to open `.ygg` sites, pick DNS servers in this section.

#### Local peer connectivity

| Setting | Meaning |
|---------|---------|
| Discoverable over multicast / Search for multicast peers | Peer with Yggdrasil nodes on the same Wi-Fi network or over USB. |
| Nearby devices over Bluetooth | Find and link to phones running the app over Bluetooth LE. Slower, but works on almost every phone. |
| Nearby devices over Wi-Fi Aware | Find and link to phones running the app over Wi-Fi Aware. Faster. Greyed out on phones without Wi-Fi Aware. |

Below the switches, the diagnostics show what each transport is doing and how many links it holds. With both switched on, a phone keeps a link over each, and Yggdrasil sends traffic over the faster one.

#### Automatic peers
A phone with Internet of its own picks public Yggdrasil peers by itself, as [YggMesh routers](https://github.com/ed-asriyan/YggMesh) do, next to the peers you add by hand. The peers come from [yggpeers](https://gitfield.org/fsky/yggpeers) instances: only those a majority of the instances report up are used, one per host, and the fastest ones (by ping) are picked. They change while the app runs, with no restart: down ones are replaced at the next check, and the list is picked again at every forced refresh.

| Setting | Meaning |
|---------|---------|
| yggpeers instances | Base URLs of the instances, `https://yggpeers.fsky.io` by default. Add your own or remove them; with none, no public peers are picked. |
| Number of peers | How many public peers to pick, 2 by default. 0 switches it off. |
| Check interval (minutes) | How often to check that the picked peers are alive, 1-1440, 30 by default. Down ones are replaced. Also checked when the network changes. |
| Forced refresh interval (days) | How often to pick the peers again even if all are alive, 1-90, 7 by default. |

## See it in action
To see the mesh working, you need two phones, at least one of them with Android 10+ and Wi-Fi Aware or Bluetooth.

1. Install YggMesh on both phones.
2. **Phone A** has Internet: it picks public peers by itself.
3. **Phone B** has none: forget its Wi-Fi networks, turn off mobile data, and add no peers.
4. Turn on Yggdrasil on both and keep them near each other. In a minute, **Peers** on phone B shows phone A as connected.
5. Open a public Yggdrasil website on phone B, e.g. from https://yggdrasil-network.github.io/services.html.

If the site loads, phone B reaches the Yggdrasil network only through phone A.

## Building it yourself

### Option A: GitHub Actions
Fork this repository and run the **Build** workflow from the Actions tab on any branch. It builds a debug APK and uploads it as an artifact of the run (`yggmesh-debug-<branch>-<run>`). Debug builds are signed with the key in `app/debug.keystore`, so they install over each other.

### Option B: Local build
Open the repository in VS Code and choose "Reopen in Container": the [dev container](.devcontainer) has everything needed. Or install it yourself:

* Rust with the Android targets and [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), NDK r25 or newer, JDK 17

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
```

* build `libyggdrasil.so` for every ABI into `app/src/main/jniLibs`

```bash
./contrib/build-yggdrasil-ng.sh
```

* build the app

```bash
./gradlew assembleDebug
```

[docs/device-testing.md](docs/device-testing.md) describes testing on phones over ADB.

### Updating Yggdrasil-ng
Yggdrasil-ng is pinned by its commit in `libs/yggdrasil-ng-jni/Cargo.toml` (`rev`, three times). Set the new commit there and `NG_VERSION` in `libs/yggdrasil-ng-jni/src/lib.rs`, then rebuild the library.

## Credits
YggMesh for Android is a fork of [yggdrasil-android](https://github.com/yggdrasil-network/yggdrasil-android) by the Yggdrasil developers.

## License

[MIT](LICENSE)
