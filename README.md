# CloudStream (Hardened & Hardware-Adaptive Edition)

[![Pre-release Build](https://github.com/abir614/cloudstream/actions/workflows/prerelease.yml/badge.svg)](https://github.com/abir614/cloudstream/actions/workflows/prerelease.yml)
[![CodeQL Security Analysis](https://github.com/abir614/cloudstream/actions/workflows/codeql.yml/badge.svg)](https://github.com/abir614/cloudstream/actions/workflows/codeql.yml)
[![Security Audit, Fuzzing & Dependency Scan](https://github.com/abir614/cloudstream/actions/workflows/security_audit_fuzz.yml/badge.svg)](https://github.com/abir614/cloudstream/actions/workflows/security_audit_fuzz.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

> [!NOTE]
> **Fork Information**: This project is a production-hardened edition of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) focused on Z+ zero-trust security, hardware-adaptive performance for any device tier (from low-end ARMv7/ARMv8 TV sticks to high-end MediaTek Helio G100 Ultimate, Dimensity, and Snapdragon devices), and native Rust acceleration.

---

## Architectural Highlights & Hardening

### 1. Z+ Zero-Trust Update Security Gate & Ephemeral Nonces
- **Isolated Cryptographic Gate ([`UpdateSecurityGate.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/utils/UpdateSecurityGate.kt))**:
  - The package installation pipeline remains locked by default.
  - Generates single-use ephemeral challenge nonces for update requests.
  - Enforces end-to-end cryptographic identity verification between the installed app and the staged APK before unlocking.
  - Unauthorized or malformed binaries are immediately purged from disk.
- **Offline Ed25519 & SHA256SUMS Attestation**:
  - Validates releases against signed `SHA256SUMS` manifests using offline Ed25519 signatures.
  - Native Rust verifier enforces strict anti-rollback protection (`apkVersionCode >= installedVersionCode`).
- **Interactive User Confirmation Dialog ([`InAppUpdater.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt))**:
  - Displays an explicit update confirmation dialog upon download completion.
  - The single-use authorization token is consumed only when the user confirms the update.

### 2. Universal Hardware-Adaptive Profile Engine
- **Chipset & SoC Profiling ([`DeviceProfile.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/utils/DeviceProfile.kt))**:
  - Dedicated architecture mapping for **MediaTek Helio G100 Ultimate**, Helio G99/G96/G85/G80, Dimensity (9400 to 6020), Qualcomm Snapdragon (8 Elite down to 4 Gen series), Google Tensor (G1–G5), Samsung Exynos, Unisoc T-series, and universal chipsets.
  - Dynamic profiling based on total system RAM, CPU cores, 64-bit ABI support, and Android Go status.
- **Battery-Adaptive Performance Scaling**:
  - **Unrestricted Battery Mode ("Disable Battery Optimization" enabled)**: Automatically boosts capable devices to the **HIGH / Boosted** tier (96 concurrent HTTP connections, 128 MB player buffer, hardware bitmap decoding).
  - **Standard Battery Mode**: Operates in **Balanced (MID)** tier to preserve battery life while maintaining smooth playback.
  - **Settings Override**: User can manually select between **Auto**, **Balanced (Standard)**, and **Boosted (High Performance)** in Settings.

### 3. Playback & Video Streaming
- **Resume Video Playback by Default**:
  - Videos automatically resume from the last saved position out of the box.
  - Periodic and lifecycle-safe playback position flushing (`onPause`, `onStop`).
- **TheIntroDB Video Skip Integration ([`VideoSkipHelper.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/utils/VideoSkipHelper.kt))**:
  - Seamless intro/recap/outro/credits skipping with graceful 404 and network fallback handling.
- **Hardware-Adaptive Buffering (ExoPlayer / Media3)**:
  - Tiers buffers dynamically: Low-end (10s min / 25s max / 12 MB target), Mid-range (20s / 45s / 48 MB), and High-end (30s / 60s / 128 MB).

### 4. Resilient Local & Sync Library
- **Bottom Navigation "Library" Menu ([`LibraryFragment.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/ui/library/LibraryFragment.kt))**:
  - Full local library lists: **Watching**, **Plan to Watch**, **Completed**, **On Hold**, **Dropped**, **Favorites**, and **Subscriptions**.
  - Null-safe initialization in [`LocalList.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/LocalList.kt) ensures all category tabs render properly even before any items are bookmarked.
  - Focus-safe ViewPager page switching preventing focus traps on Android TV and phone interfaces.
- **Multi-Account Sync & Subtitle Providers ([`AccountManager.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/syncproviders/AccountManager.kt))**:
  - Integrated support for MyAnimeList (MAL), AniList, Kitsu, Simkl, and LocalList.
  - Subtitle integration with OpenSubtitles, SubDL, Addic7ed, SubSource, and AnimeSkip.

### 5. Network & Control-Plane Security
- **Strict Control Plane vs. Data Plane ([`network_security_config.xml`](file:///home/x/Projects/CS/cloudstream/app/src/main/res/xml/network_security_config.xml))**:
  - Control-plane domains (`github.com`, `githubusercontent.com`) strictly enforce HTTPS and system-only CAs.
  - User-installed CAs are excluded in release builds to eliminate unauthorized MitM interception.
- **SSRF-Safe DNS Gate ([`SafeDns.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/SafeDns.kt))**:
  - Enforces address validation at the DNS resolution layer for all network calls and HTTP redirect hops.
  - Blocks loopback (`127.0.0.0/8`, `::1`), RFC-1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), link-local / cloud metadata (`169.254.169.254`), and IPv6 ULA (`fc00::/7`).
  - Opt-in LAN toggle available for local media servers (Jellyfin, Kodi, local NAS).
- **Resilient Multi-Tier DNS with Hard Time Budget ([`DohProviders.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/DohProviders.kt))**:
  - Multi-tier resolver (Mullvad DoH, Quad9, Cloudflare, Google, UDP fallback) bounded by a hard 2-second budget before falling back to system DNS.

### 6. Native Rust Acceleration & Safety (`rust-core`)
- **Accelerated Components**:
  - Zero-allocation IPTV M3U/M3U8 master playlist and cue parser.
  - Native Levenshtein fuzzy search scoring and string compression (DEFLATE).
  - High-performance adult & hentai classification engine.
  - Authenticated encrypted backups using Argon2id + ChaCha20-Poly1305 (`CSENC1` format).
- **FFI / JNI Boundary Hardening ([`jni_bridge.rs`](file:///home/x/Projects/CS/cloudstream/rust-core/src/jni_bridge.rs))**:
  - All JNI entry points are null-checked and wrapped with `std::panic::catch_unwind` to prevent Rust panics from unwinding across the FFI boundary.
  - Built with `panic = "unwind"`, `opt-level = "z"`, and fat LTO.

### 7. Content Safety: Default NSFW & Hentai Filtering
- **Hybrid Content Classifier ([`NsfwFilterEngine.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/domain/engine/NsfwFilterEngine.kt))**:
  - Filters adult and hentai content by default across Feed, Carousel, Search, and Extension discovery.
  - Detects disguised adult content tagged under `TvType.Anime` or `TvType.OVA` without false positives on clean mainstream anime (e.g., Attack on Titan, Hunter x Hunter, Spy x Family).
  - Can be unlocked in Settings → Providers.

### 8. Streamlined Release Packaging
Each CI release generates exactly 6 organized artifacts with zero duplicate files:
* `CloudStream-prerelease-v<BUILD>-arm64-v8a.apk`: Optimized for modern 64-bit ARM devices (Helio G100, Snapdragon, Dimensity).
* `CloudStream-prerelease-v<BUILD>-armeabi-v7a.apk`: For older 32-bit devices and budget Android TV sticks.
* `CloudStream-prerelease-v<BUILD>-universal.apk`: All-in-one universal binary.
* `SHA256SUMS`: Cryptographic integrity manifest for offline attestation.
* `classes.jar`: API stub for provider and plugin developers.
* `app-sources.jar`: API source documentation for extensions.

---

## Documentation Links

* [**`FORK_CHANGES.md`**](file:///home/x/Projects/CS/cloudstream/FORK_CHANGES.md): Comprehensive itemized log of all differences against upstream.
* [**`SECURITY.md`**](file:///home/x/Projects/CS/cloudstream/SECURITY.md): Security policy, reporting procedures, and architectural guarantees.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE) in accordance with the upstream CloudStream project.
