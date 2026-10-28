# CloudStream Fork: Documented Architectural Differences

This document catalogues all intentional architectural deviations, security hardenings, and feature adjustments between this fork (`abir614/cloudstream`) and upstream (`recloudstream/cloudstream`).

---

## 1. Upstream Synchronization & Provenance
- **Upstream Repository**: [`recloudstream/cloudstream`](https://github.com/recloudstream/cloudstream)
- **Git Remote**: `upstream` (`https://github.com/recloudstream/cloudstream.git`)
- **Fork Goal**: Ultra-low memory and CPU footprint on low-end hardware (ARMv7/ARMv8 TV sticks with 500MB RAM up to flagship SoCs), strict control-plane security, and verifiable defense-in-depth properties.

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
- **DNS-Layer Inspection**: All hostnames resolved by OkHttp (including initial connections and subsequent redirect hops) are inspected by [`SafeDns`](app/src/main/java/com/lagradost/cloudstream3/network/SafeDns.kt).
- **Blocked Ranges**:
  - IPv4 Loopbacks (`127.0.0.0/8`), IPv6 Loopback (`::1`)
  - RFC 1918 Private Ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
  - Cloud Metadata / Link-Local (`169.254.169.254`, `169.254.0.0/16`)
  - Carrier-Grade NAT (`100.64.0.0/10`)
  - Reserved / Experimental (`0.0.0.0/8`, `240.0.0.0/4`)
  - IPv6 Unique Local Address (`fc00::/7`)
- **LAN Access**: Opt-in toggle (`allowLan`) available for streaming from private LAN media servers (e.g., Jellyfin, Kodi, local NAS).

### 2.4 Privacy-First Resilient Multi-Tier DNS with Hard Budget
- **Privacy-First Fallback Chain in [`DohProviders.kt`](app/src/main/java/com/lagradost/cloudstream3/network/DohProviders.kt)**:
  1. **Tier 1 (Privacy DoH)**: Queries zero-logging **Mullvad DoH** $\rightarrow$ **Quad9 DoH** first.
  2. **Tier 2 (High-Speed Edge DoH)**: Queries **Cloudflare DoH** $\rightarrow$ **Google DoH** within remaining budget.
  3. **Tier 3 (Privacy Plain DNS)**: Direct UDP 53 Anycast to Mullvad (`194.242.2.2`, `193.19.108.2`) and Quad9 (`9.9.9.9`, `149.112.112.112`).
  4. **Tier 4 (Edge Plain DNS)**: Direct UDP 53 Anycast to Cloudflare (`1.1.1.1`, `1.0.0.1`) and Google (`8.8.8.8`, `8.8.4.4`).
  5. **Tier 5 (System Fallback)**: Transparent Android `Dns.SYSTEM` fallback.
- **Bounded Total Budget**: Enforces a 2000ms hard deadline per lookup to prevent sequential fallbacks from stacking latency on slow connections.
- **ExoPlayer / Media3 DNS Alignment**: When DoH is enabled (`dns != 0`), Cronet is automatically bypassed in [`CS3IPlayer.kt`](app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt) so video chunks are retrieved via OkHttp `app.baseClient`, guaranteeing video segment downloads never leak to unencrypted ISP DNS.

### 2.5 Scoped Permissive SSL Client
- **Isolated Scope**: [`SSLTrustManager`](app/src/main/java/com/lagradost/cloudstream3/ui/player/SSLTrustManager.kt) is strictly confined to individual video playback instances and `insecureApp`.
- **Resource Reuse**: Insecure clients are derived via `app.baseClient.newBuilder().ignoreAllSSLErrors().build()`, sharing the existing thread pool, dispatcher, and connection pool without wasting memory.
- **Zero JVM Tampering**: `HttpsURLConnection.setDefaultSSLSocketFactory` and `setDefaultHostnameVerifier` are never modified.

### 2.6 Release Signing Hygiene
- **No Committed Keystores**: The release keystore file is purged from tracking and excluded via `.gitignore`.
- **No Hardcoded Passwords**: Gradle release signing requires environment secrets (`CS_KEYSTORE_PATH`, `CS_KEYSTORE_PASSWORD`, etc.) and produces unsigned builds if secrets are absent.

### 2.7 Data at Rest & Strict Anti-Rollback Gating
- **No Unencrypted OS Backups**: `android:allowBackup="false"` enforced in `AndroidManifest.xml`, along with explicit deny rules in `data_extraction_rules.xml` and `backup_descriptor.xml` preventing unencrypted ADB or cloud extraction of credentials/keys.
- **Strict Anti-Rollback (§2.3)**: Both [`UpdateSecurityGate`](app/src/main/java/com/lagradost/cloudstream3/utils/UpdateSecurityGate.kt) and native Rust verifier strictly enforce `apkVersionCode > installedVersionCode` (rejecting both downgrades and non-upgrading reinstallations).

### 2.8 Offline Ed25519 Update Manifest Attestation (§2.3)
- **Air-Gapped Trust**: Update authenticity verification using offline Ed25519 signature checks on `SHA256SUMS` manifests, verified via `ed25519-dalek` in the native Rust core (`NativeCoreBridge.verifyEd25519`).
- **Fail-Closed Download Gate**: `UpdateSecurityGate.verifyAndAuthorize` asserts the computed SHA-256 against signed manifests before permitting installation.

### 2.9 Authenticated Encrypted Backups (§4.1)
- **Native Argon2id + ChaCha20-Poly1305**: Backups can be exported with an optional user passphrase, encrypted with authenticated memory-hard Argon2id key derivation and ChaCha20-Poly1305 encryption (`CSENC1` format).
- **Tamper Detection**: Poly1305 authentication tags guarantee corrupted or wrong-passphrase backups fail closed without modifying app state.

---

## 3. Native Rust Core & JNI Hardening

### 3.1 Accelerated Components
- **Zero-Allocation IPTV Parser**: High-speed M3U/M3U8 master playlist and cue parser eliminating JVM heap churn.
- **Fuzzy Levenshtein Search**: Native search matching and scoring.
- **DEFLATE Compression**: In-memory string compression/decompression.
- **Content Classifier**: Native adult & hentai classification engine.

### 3.2 FFI / JNI Boundary Safety
- **Panic Boundary Safety**: Every JNI function in [`jni_bridge.rs`](rust-core/src/jni_bridge.rs) wraps Rust logic in `std::panic::catch_unwind` and validates input pointers, ensuring panics never cross the FFI boundary as undefined behavior.
- **Release Profile**: Configured with `panic = "unwind"`, `opt-level = "z"`, and `lto = "fat"`.

### 3.3 Lean Release Profile & Feature-Gated WASM Sandbox (§3.5, §6.2)
- **Feature-Gated Runtime**: The `wasmi` WebAssembly engine is optional and disabled by default under `features = ["wasm-sandbox"]`, reducing binary size and memory overhead on mobile and TV sticks.

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

### 4.3 Image Caching & Decoding (Coil) (§5.5)
- **LOW**: 10% memory cache, crossfade disabled, hardware bitmaps disabled, `RGB_565` bitmap configuration (halving poster memory consumption).
- **MID**: 20% memory cache, 150ms crossfade, `ARGB_8888`.
- **HIGH**: 25% memory cache, 250ms crossfade, `ARGB_8888`.

### 4.4 Media3 / ExoPlayer LoadControl Buffers (§5.6)
- **LOW**: Minimum buffer: 10s, maximum buffer: 25s, target buffer: 12 MB, back buffer: 5s.
- **MID**: Minimum buffer: 20s, maximum buffer: 45s, target buffer: 48 MB, back buffer: 15s.
- **HIGH**: Minimum buffer: 30s, maximum buffer: 60s, target buffer: 128 MB, back buffer: 30s.

### 4.5 Packaging & Native ABI Optimization (§6.1)
- **Focused ABI Splits**: Builds package `arm64-v8a` and `armeabi-v7a` plus an optional universal APK, dropping obsolete x86 emulators from releases.
- **Page-Aligned Uncompressed Native Libs**: Configured `useLegacyPackaging = false` and `android:extractNativeLibs="false"`, allowing Android to `mmap` native libraries directly from the APK rather than extracting them onto flash storage, speeding up cold startup and saving storage space.

### 4.6 Memory Pressure Trimming & Connection Eviction (§5.3)
- **`onTrimMemory` & `onLowMemory`**: On moderate/low/critical memory pressure, the app automatically flushes Coil bitmap memory caches and evicts all idle OkHttp network connections via `baseClient.connectionPool.evictAll()`.

### 4.7 CI Release Hygiene & Integrity Verification (§4.4)
- **Secret-Only Signing**: Fallback passwords removed; unsigned if signing secrets are absent.
- **Automated Checksums & Manifests**: CI workflows compute and publish `SHA256SUMS*` for all built APKs, ready for offline signature attestation.

---

## 5. Content Filtering: Default NSFW & Anime-Blended Hentai

- **Hybrid Filtering**: Hybrid Rust native token analysis and JVM token matching.
- **Anime Disguise Detection**: Reliably identifies hentai tagged as `TvType.Anime` or `TvType.OVA` by title tokens, URL slug analysis, adult studio markers, and tags without false positives on clean mainstream anime (e.g. Attack on Titan, Hunter x Hunter, Assassination Classroom, Spy x Family, Frieren, Demon Slayer).
- **Default-Off Safety**: Adult content is filtered out by default on Feed, Carousel, Search, and Extension discovery. Unlocked only when user explicitly toggles `enable_nsfw_on_providers_key`.
- **500MB Potato Device & Android TV Optimization ([`OnDeviceNsfwClassifier.kt`](app/src/main/java/com/lagradost/cloudstream3/domain/engine/OnDeviceNsfwClassifier.kt))**:
  - On devices with $\le 1024\text{ MB}$ total RAM, `isLowRamDevice` (Android Go), or `DeviceTier.LOW`, heavy TFLite native tensor arenas (35MB+ native RAM) are completely bypassed in favor of a zero-allocation, sub-millisecond (< 0.05ms) multi-spectrum skin chrominance analyzer (`classifyPotatoFastScore`) consuming $< 15\text{ KB}$ heap on a static 48×48 RGB_565 scratchpad.
  - **Backdrop and Noise Rejection**: Dual-zone ratio evaluation rejects monochromatic yellow/gold/beige poster art (> 60% uniform upper and body skin hue) and dispersed noise (< 22% concentrated row skin density), guaranteeing clean mainstream media is never falsely blocked.
  - **Butter-Smooth 60/120fps UI**: safe `WeakReference<Drawable>` task queuing with active scroll throttling prevents GC sweeps and eliminates frame stuttering even during rapid remote scrolling. Full TV D-pad focus grid geometry is preserved to prevent Leanback remote navigation crashes.

---

## 6. Full Local & Sync Account Providers

All primary third-party sync and subtitle accounts are wired and hardened with crash-resilient null-safe state stores:
- **Account Sync Providers**: MyAnimeList (MAL), Kitsu, AniList, Simkl, and LocalList.
- **Subtitle Providers**: OpenSubtitles, SubDL, Addic7ed, SubSource, AnimeSkip.
- **Null-Safe Local Library**: `LocalList` and `DataStoreHelper` guarantee non-null list returns, allowing the library tabs (Watching, Plan to Watch, Completed, On Hold, Dropped, Favorites, Subscriptions) to render properly even before any items are saved.

---

## 7. Z+ Zero-Trust In-App Updates & Streamlined Releases

- **Z+ Isolated Security Gate**: Updates require an ephemeral single-use challenge nonce and pass package identity, anti-rollback, and signing certificate fingerprint validation before execution.
- **Interactive Update Confirmation**: Prompts the user upon download completion and consumes the single-use token only when the user confirms.
- **Streamlined Packaging**: Each release generates exactly 6 artifacts (`arm64-v8a`, `armeabi-v7a`, `universal`, `SHA256SUMS`, `classes.jar`, `app-sources.jar`), eliminating duplicate untagged binaries.

---

## 8. Security Advisories & Dependency Upgrades
- **Google Guava (CVE-2026-102554 / GHSA-xxph-c9ww-hj94)**: Enforced `com.google.guava:guava:33.7.2-android` via Gradle `resolutionStrategy` to eliminate uncontrolled deserialization memory allocation DoS vulnerabilities.
- **Jackson Databind (CVE-2026-59889 / GHSA-5gvw-p9qm-jgwh)**: Validated zero usage of `@JsonView` annotations across the entire codebase. Strictly pinned Jackson `2.13.5` to maintain `minSdk 23` backwards compatibility for Android TV and Fire TV sticks without risk.
- **Rust Core `ctutils` (RUSTSEC-2026-0385)**: Upgraded `ctutils` to `0.4.4` to fix non-constant-time mask generation and `u128` comparisons, and replaced yanked `inout 0.2.3` with `0.2.5`.
- **Media3 & NextLib Strictly Pinned**: Retained `androidx.media3 = 1.9.3` and strictly pinned `nextlibMedia3 = { strictly = "1.9.3-0.12.0" }`. Upgrading to 1.11.1+ breaks the internal package bindings in `androidx.media3.extractor.mkv` (`UpdatedMatroskaExtractor.kt`). Strictly pinning ensures automated bots do not introduce binary/symbol compilation breakage.
- **Resolved Pull Requests**: Merged Dependabot PRs #27 (kotlinx.serialization 1.12.0), #28 (serde_json 1.0.154), #29 (nextlibMedia3), #30 (ed25519-dalek 3.0.0), and #31 (appcompat 1.8.0).

---

## 9. On-Demand Deployment via GitHub Actions
- **Manual Stable Release**: Supported via `workflow_dispatch` on `release.yml`. Users can deploy signed production releases anytime directly through GitHub Actions with custom or auto-incrementing tags.

---

## 10. Cold/Warm Start Acceleration, Granular Timeouts & Kernel Tuning

### 10.1 Instant Warm Starts & Persistent Main Page Cache
- **Persistent Disk Serialization (`MainPageDiskCache`)**: Serializes homepage feed responses to a dedicated app cache directory (`cs3_mainpage_cache`) with a 24-hour TTL.
- **Stale-While-Revalidate Rendering**: `HomeViewModel` loads from memory or disk cache immediately on cold/warm startup. The homepage populates instantly without showing a blank screen or wiping existing UI, revalidating against upstream providers in the background.
- **Coil Poster Cache Retention**: Configured disk cache size (up to 1GB on high tiers, 128MB on low tiers) persists across process exits without premature truncation.

### 10.2 Startup Blank Screen Elimination
- **Diff-Commit Bound Lifecycle**: In `HomeFragment`, loading shimmer indicators are dismissed and content recycler views made visible strictly within `AsyncListDiffer`'s commit callback. Shimmer only clears when child view holders have completed binding and layout, eliminating the 2-3 second blank pause during initial load.
- **Protected Revalidation State**: `Resource.Loading` preserves rendered items when revalidating an already-populated list (`itemCount > 1`), preventing full-screen clearing during background refreshes.

### 10.3 Source Resolution Timeouts
- **Overall Source Resolution Timeout**: Wrapped content generation in `withTimeoutOrNull(60_000L)` across `ResultViewModel2` and `PlayerGeneratorViewModel`, bounding total source resolution to 1 minute.
- **Per-Source Extractor Timeout**: Wrapped individual extractor resolution in `withTimeout(5_000L)` (`ExtractorApi.kt`). Any hanging or unresponsive host extractor terminates after 5 seconds without stalling remaining extractors. Parent coroutine cancellation is preserved.

### 10.4 Hardware Acceleration & Kernel/zRAM Tuning
- **Explicit Window Hardware Acceleration**: Enforces `android:hardwareAccelerated="true"` under `<application>` in `AndroidManifest.xml` to prevent budget TV box firmwares from disabling the 2D OpenGL ES/Vulkan RenderThread pipeline.
- **Linux Kernel Inspection**: Detects active kernel version via `/proc/version` (`getKernelVersion()`) and checks for legacy Linux kernels `< 4.9` (`isLegacyLinuxKernel()`).
- **zRAM Swap Detection**: Reads total swap allocation from `/proc/meminfo` (`getZramSwapMb()`).
- **Composite Ultra-Low Memory / Potato Engine (`isUltraPotato`)**:
  - Dynamically triggers if $\le 1024\text{ MB}$ RAM, $\le 1536\text{ MB}$ RAM with zero zRAM swap, or $\le 2048\text{ MB}$ RAM on legacy Linux kernels.
  - Search concurrency restricted to 2 concurrent tasks.
  - Video playback buffer bounded to 6MB target and 2s back-buffer.
  - Download buffer limit bounded to 5MB (down from 10MB/80MB).
  - Coil image memory cache constrained to 5% of available heap.
  - RecyclerView `itemViewCacheSize` dynamically scaled across both master feeds and individual horizontal rows (`HomeParentItemAdapter`, `SearchFragment`, `HomeFragment`).

