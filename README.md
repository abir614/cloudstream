# CloudStream (Hardened & Optimized Edition)

> [!NOTE]
> **Fork Information**: This project is a customized fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) focused on security hardening, hardware-adaptive performance for low-end hardware (ARMv7/ARMv8 TV sticks and phones), and native Rust acceleration.

---

### Architectural Status & Hardening Details

#### 1. Network & Control-Plane Security
- **Strict Control Plane vs. Data Plane ([`network_security_config.xml`](file:///home/x/Projects/CS/cloudstream/app/src/main/res/xml/network_security_config.xml))**:
  - Control-plane domains (`github.com`, `githubusercontent.com` for releases, manifests, and repositories) enforce strict HTTPS and system-only CAs.
  - User-installed CAs are disabled in release builds to eliminate unauthorized MitM interception.
  - Cleartext HTTP is restricted to the streaming/scraping data plane where external video sources require it.
- **SSRF-Safe DNS Gate ([`SafeDns.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/SafeDns.kt))**:
  - Enforces address validation at the DNS resolution layer for all network calls and HTTP redirect hops.
  - Blocks loopback (`127.0.0.0/8`, `::1`), RFC-1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), link-local / cloud metadata (`169.254.169.254`), and IPv6 ULA (`fc00::/7`).
  - Opt-in LAN access is available for local network media servers (NAS / Jellyfin / Kodi).
- **Resilient DNS with Time-Budget Gating ([`DohProviders.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/network/DohProviders.kt))**:
  - Multi-tier resolver (Mullvad DoH, Quad9, Cloudflare, Google, UDP fallback) bounded by a hard 2-second total budget per lookup before falling back to system DNS.

#### 2. Least-Privilege Permissions
- **Pruned Manifest Permissions**:
  - Removed dangerous `MANAGE_EXTERNAL_STORAGE` (relies on MediaStore and standard Scoped Storage).
  - Removed `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (updates require standard Android package installer user confirmation).
  - Removed `QUERY_ALL_PACKAGES` (replaced with an explicit `<queries>` block for supported external video players and torrent clients).

#### 3. Native Rust Acceleration & Boundaries
- **Native Rust Engine (`rust-core`)**:
  - Zero-allocation IPTV M3U/M3U8 master playlist and cue parser.
  - Native Levenshtein fuzzy search scoring and string compression (DEFLATE).
  - High-performance adult & anime-blended hentai token classifier.
- **Panic-Safe JNI Boundaries ([`jni_bridge.rs`](file:///home/x/Projects/CS/cloudstream/rust-core/src/jni_bridge.rs))**:
  - JNI entry points are guarded against null pointers and wrapped with `std::panic::catch_unwind` to ensure Rust panics cannot unwind across the FFI boundary into undefined behavior.
  - Release profile uses `panic = "unwind"`, `opt-level = "z"`, and fat LTO.

#### 4. Content Safety: Default NSFW & Hentai Filtering
- **Hybrid Content Classifier ([`NsfwFilterEngine.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/domain/engine/NsfwFilterEngine.kt))**:
  - Filters adult and hentai content by default across Feed, Carousel, Search, and Extension discovery.
  - Distinguishes and detects disguised hentai categorized by scraping providers under `TvType.Anime` or `TvType.OVA` without false positives on clean mainstream anime (e.g., Attack on Titan, Hunter x Hunter, Assassination Classroom, Spy x Family).
  - Can be explicitly enabled in Settings → Providers (`enable_nsfw_on_providers_key`), synchronizing immediately in memory.

#### 5. Hardware-Adaptive Profiling
- **Device Profile Engine ([`DeviceProfile.kt`](file:///home/x/Projects/CS/cloudstream/app/src/main/java/com/lagradost/cloudstream3/utils/DeviceProfile.kt))**:
  - Dynamically classifies hardware into tiers (`LOW`, `MID`, `HIGH`) based on device RAM, Android Go status, and 64-bit ABI support.
  - Tiers OkHttp networking (`LOW`: 24 requests / 4 per host / 3 idle connections; `MID`: 48/5/5; `HIGH`: 64/6/8) to avoid CPU exhaustion and rate-limiting on weak TV sticks.

#### 6. Plugin Execution & Transparency
- **Plugin Architecture Notice**: Plugins currently execute in-process via `PathClassLoader` (same as upstream CloudStream). An isolated-process IPC host model for data-only scrapers is on the roadmap.
- **Release Signing**: Release keystores are strictly excluded from git history. Release builds are signed only via CI environment secrets (`CS_KEYSTORE_PATH`).

---

### License

This project is licensed under the [GNU General Public License v3.0](LICENSE) in accordance with the upstream CloudStream project.
