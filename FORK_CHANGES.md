# CloudStream Fork: Documented Architectural Differences

This document catalogues all intentional architectural deviations, security hardenings, and feature adjustments between this fork (`abir614/cloudstream`) and upstream (`recloudstream/cloudstream`).

---

## 1. Upstream Synchronization & Provenance
- **Upstream Repository**: [`recloudstream/cloudstream`](https://github.com/recloudstream/cloudstream)
- **Git Remote**: `upstream` (`https://github.com/recloudstream/cloudstream.git`)
- **Fork Goal**: Ultra-low memory and CPU footprint on low-end hardware (ARMv7/ARMv8 TV sticks and phones), strict control-plane security, and verifiable defense-in-depth properties.

---

## 2. Security & Network Hardening

### 2.1 Least-Privilege Permissions
- **`MANAGE_EXTERNAL_STORAGE`**: Removed. Downloads and storage access use MediaStore and standard Scoped Storage.
- **`UPDATE_PACKAGES_WITHOUT_USER_ACTION`**: Removed. Application updates strictly require standard user confirmation via Android Package Installer.
- **`QUERY_ALL_PACKAGES`**: Removed. Application package visibility is restricted to the explicit `<queries>` block containing supported video players (VLC, mpv, Just Player, Next Player) and torrent clients.

### 2.2 Strict Network Security Configuration
- **Control Plane Isolation**: Cleartext HTTP is strictly forbidden for control-plane domains (`github.com`, `githubusercontent.com`) under `<domain-config>`.
- **User CA Elimination**: User-installed CAs are excluded from release builds to prevent rogue or untrusted MitM proxy interception. User certificates are confined strictly to `<debug-overrides>`.
- **UsesCleartextTraffic**: Removed `android:usesCleartextTraffic="true"` from `AndroidManifest.xml`. Cleartext is permitted only for video stream data-plane endpoints using system CAs.

### 2.3 SSRF-Safe DNS Resolution (`SafeDns`)
- **DNS-Layer Inspection**: All hostnames resolved by OkHttp (including initial connections and subsequent redirect hops) are inspected by [`SafeDns`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/SafeDns.kt).
- **Blocked Ranges**:
  - IPv4 Loopbacks (`127.0.0.0/8`), IPv6 Loopback (`::1`)
  - RFC 1918 Private Ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
  - Cloud Metadata / Link-Local (`169.254.169.254`, `169.254.0.0/16`)
  - Carrier-Grade NAT (`100.64.0.0/10`)
  - Reserved / Experimental (`0.0.0.0/8`, `240.0.0.0/4`)
  - IPv6 Unique Local Address (`fc00::/7`)
- **LAN Access**: Opt-in toggle (`allowLan`) available for streaming from private LAN media servers (e.g., Jellyfin, Kodi, local NAS).

### 2.4 Resilient Multi-Tier DNS with Hard Budget
- **Fallback Chain**: Resolves across Mullvad DoH, Quad9, Cloudflare, Google, and plain UDP 53.
- **Bounded Total Budget**: Enforces a 2000ms hard deadline per lookup in [`DohProviders.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/DohProviders.kt) to prevent sequential fallbacks from stacking latency on slow connections. Falls back cleanly to System DNS.

### 2.5 Scoped Permissive SSL Client
- **Isolated Scope**: [`SSLTrustManager`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/SSLTrustManager.kt) is strictly confined to individual video playback instances and `insecureApp`.
- **Resource Reuse**: Insecure clients are derived via `app.baseClient.newBuilder().ignoreAllSSLErrors().build()`, sharing the existing thread pool, dispatcher, and connection pool without wasting memory.
- **Zero JVM Tampering**: `HttpsURLConnection.setDefaultSSLSocketFactory` and `setDefaultHostnameVerifier` are never modified.

### 2.6 Release Signing Hygiene
- **No Committed Keystores**: The release keystore file is purged from tracking and excluded via `.gitignore`.
- **No Hardcoded Passwords**: Gradle release signing requires environment secrets (`CS_KEYSTORE_PATH`, `CS_KEYSTORE_PASSWORD`, etc.) and produces unsigned builds if secrets are absent.

---

## 3. Native Rust Core & JNI Hardening

### 3.1 Accelerated Components
- **Zero-Allocation IPTV Parser**: High-speed M3U/M3U8 master playlist and cue parser eliminating JVM heap churn.
- **Fuzzy Levenshtein Search**: Native search matching and scoring.
- **DEFLATE Compression**: In-memory string compression/decompression.
- **Content Classifier**: Native adult & hentai classification engine.

### 3.2 FFI / JNI Boundary Safety
- **Panic Boundary Safety**: Every JNI function in [`jni_bridge.rs`](file:///home/x/Projects/CS/cloudstream/rust-core/src/jni_bridge.rs) wraps Rust logic in `std::panic::catch_unwind` and validates input pointers, ensuring panics never cross the FFI boundary as undefined behavior.
- **Release Profile**: Configured with `panic = "unwind"`, `opt-level = "z"`, and `lto = "fat"`.

---

## 4. Hardware-Adaptive Profiling (`DeviceProfile`)

### 4.1 Device Tiers
- **`LOW`**: Devices with ≤2GB total RAM, 32-bit CPU, or `ActivityManager.isLowRamDevice` (Android Go).
- **`MID`**: Devices with ≤4GB total RAM.
- **`HIGH`**: Devices with >4GB total RAM.

### 4.2 Network Concurrency Scaling
- **LOW**: Max requests: 24, max per host: 4, idle connections: 3, keep-alive: 1 min.
- **MID**: Max requests: 48, max per host: 5, idle connections: 5, keep-alive: 3 min.
- **HIGH**: Max requests: 64, max per host: 6, idle connections: 8, keep-alive: 5 min.

---

## 5. Content Filtering: Default NSFW & Anime-Blended Hentai

- **Hybrid Filtering**: Hybrid Rust native token analysis and JVM token matching.
- **Anime Disguise Detection**: Reliably identifies hentai tagged as `TvType.Anime` or `TvType.OVA` by title tokens, URL slug analysis, adult studio markers, and tags without false positives on clean mainstream anime (e.g. Attack on Titan, Hunter x Hunter, Assassination Classroom, Spy x Family).
- **Default-Off Safety**: Adult content is filtered out by default on Feed, Carousel, Search, and Extension discovery. Unlocked only when user explicitly toggles `enable_nsfw_on_providers_key`.

---

## 6. Clean Core: Removed Sync & Subtitle Providers

In upstream CloudStream, several third-party sync and subtitle accounts are bundled. In this fork, they are omitted to reduce background sync services, APK size, and external attack surface:
- **Account Sync Providers**: MyAnimeList (MAL), Kitsu, AniList, Simkl.
- **Subtitle Providers**: OpenSubtitles, SubDL, Addic7ed, SubSource, AnimeSkip.

*(All removed providers are documented here and in the README; dead UI settings are omitted.)*
