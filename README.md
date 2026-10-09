# CloudStream (Hardened & Optimized Edition)

> [!NOTE]
> **Fork Information**: This project is cloned from the upstream [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) repository and customized for personal usage with enhanced **Z+ Multi-Layer Zero-Trust Isolation**, a native **Rust Core Engine**, and **Resource Optimizations** for low-end devices (ARMv7/ARMv8 TV sticks and boxes).

---

### Key Enhancements & Security Layering

1. **Zero-Trust Multi-Layer Sandbox Isolation**:
   - **Kernel-Level OS Isolation**: Untrusted plugin operations run inside an Android Service declared with `android:isolatedProcess="true"`. The Linux kernel enforces a dedicated UID with **ZERO Android permissions**—even if the user grants `MANAGE_EXTERNAL_STORAGE` or `REQUEST_INSTALL_PACKAGES` to the main app, the sandbox process cannot access files, device storage, or package installers.
   - **Capability-Gated WASM Runtime**: Sandboxed extensions execute in an embedded WebAssembly runtime ([`wasmi`](https://github.com/wasmi-labs/wasmi)) with strict linear memory limits and **CPU fuel consumption quotas** to prevent infinite loop DoS attacks.
   - **Anti-SSRF Network Gate**: Enforces strict URL validation blocking loopbacks (`127.0.0.1`), private RFC-1918 subnets (`192.168.0.0/16`, `10.0.0.0/8`), cloud metadata endpoints (`169.254.169.254`), and non-HTTP schemas (`file://`, `content://`).

2. **Ultra-Low Memory Footprint for Low-End Devices**:
   - **Zero-Copy IPTV Parser**: Native Rust streaming M3U/M3U8 parser processes 50,000+ channel playlists in milliseconds with minimal heap footprint, eliminating JVM garbage collection stutters on 512MB–1GB RAM TV sticks.
   - **Dual Architecture Split APKs**: Gradle automatically packages dedicated builds for **ARMv7** (`armeabi-v7a`), **ARMv8** (`arm64-v8a`), and **Universal** architectures to minimize APK size and memory overhead.

3. **CI/CD & Security Audits**:
   - Automated GitHub Actions workflow compiling native Rust NDK binaries for ARMv7 and ARMv8.
   - Integrated automated security audits (`cargo audit`), clippy security checks, test fuzzing, and Trivy vulnerability scans.

---

### Architecture Overview

```
[Main App Process (UID: 10234)]                 [Extension Sandbox Process (Isolated UID: 99042)]
• User-Granted Permissions Live Here            • android:isolatedProcess="true"
• UI & Hardware Media Player (ExoPlayer)        • ZERO Android Permissions Granted by Linux Kernel
                                                • CANNOT touch Storage, Files, or Package Installers
                        ▲                                               ▲
                        │                                               │
                        └─── [IPC Communication: Only Parsed Metadata] ──┘
                                                                        │
                                              ┌─────────────────────────┴────────────────────────┐
                                              ▼                                                  ▼
                                 [Wasmi WebAssembly Runtime]                         [Anti-SSRF Network Gate]
                                 • Strict Linear Memory Boundary                     • Blocks 127.0.0.1, RFC-1918
                                 • CPU Fuel/Instruction Quota Limiting               • Blocks 169.254.169.254 (Cloud)
                                 • Zero Direct OS System Calls                       • Whitelists HTTP/HTTPS only
```

---

### License

This project is licensed under the [GNU General Public License v3.0](LICENSE) in accordance with the upstream CloudStream project.
