# Security Policy

## Supported Versions

| Version | Supported          | Description                                  |
| ------- | ------------------ | -------------------------------------------- |
| 4.8.x   | :white_check_mark: | Hardened Edition (Guarded In-Process Sandboxing & SSRF Gate) |
| < 4.8.0 | :x:                | Legacy CloudStream releases                  |

## Defense-in-Depth Architecture & Threat Model

This fork implements multi-layer defense-in-depth isolation:
1. **Guarded In-Process Extension Sandboxing**: Third-party `.cs3` plugins are loaded through `ShieldedClassLoader` (intercepting and blocking reflection access to app authentication tokens, keystores, and internal singletons) and executed inside `SandboxedContextWrapper` (isolating storage, caches, and preferences under dedicated directories, and strictly preventing access to core app databases).
2. **Trust-On-First-Use (TOFU) Cryptographic Fingerprinting**: Computes and pins SHA-256 digests of installed plugins in SQLite. Automatically grandfathers existing extensions, verifies local disk integrity at startup, and alerts the user if a plugin binary is tampered with or substituted by a rogue repository.
3. **Anti-SSRF Network Gate**: Enforces address validation at the DNS resolution layer for all network calls and HTTP redirect hops via `SafeDns`, blocking loopbacks (`127.0.0.0/8`, `::1`), RFC-1918 private subnets, Carrier-Grade NAT (`100.64.0.0/10`), link-local / cloud metadata (`169.254.169.254`), and IPv6 ULA.
4. **Isolated Process Gate**: Runs `ExtensionSandboxService` in `android:isolatedProcess="true"` with a dedicated throwaway Linux UID with zero Android OS permissions for pre-flight URL and redirect safety validation.
5. **Experimental WASM Sandboxing**: Embedded `wasmi` WebAssembly engine in native Rust core with instruction/fuel consumption quotas for next-generation scraper modules.

## Reporting a Vulnerability

We take the security of this project seriously. If you discover a vulnerability or security flaw (especially sandbox escape vectors or SSRF bypasses):

1. **Do not open a public issue.**
2. Report the vulnerability privately via **[GitHub Private Vulnerability Reporting](https://github.com/abir614/cloudstream/security/advisories/new)**.
3. Please provide:
   - A clear description of the vulnerability.
   - Proof of Concept (PoC) or reproduction steps.
   - Potential impact on the device or application sandbox.

We will review, verify, and address reported security issues promptly.
