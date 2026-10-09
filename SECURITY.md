# Security Policy

## Supported Versions

| Version | Supported          | Description                                  |
| ------- | ------------------ | -------------------------------------------- |
| 4.8.x   | :white_check_mark: | Hardened Edition (with WASM & Process Isolation) |
| < 4.8.0 | :x:                | Legacy CloudStream releases                  |

## Zero-Trust Architecture & Threat Model

This fork implements multi-layer zero-trust isolation:
1. **Kernel-Level Isolation**: Extension operations execute in an Android service with `android:isolatedProcess="true"`. The OS assigns an isolated UID with zero Android permissions.
2. **WASM Runtime Sandboxing**: Extensions execute within linear memory boundaries using the embedded `wasmi` engine with strict instruction/fuel consumption quotas.
3. **Anti-SSRF Network Gate**: Blocks private RFC-1918 subnets, loopbacks (`127.0.0.1`), and cloud metadata (`169.254.169.254`).

## Reporting a Vulnerability

We take the security of this project seriously. If you discover a vulnerability or security flaw (especially sandbox escape vectors or SSRF bypasses):

1. **Do not open a public issue.**
2. Report the vulnerability privately via **[GitHub Private Vulnerability Reporting](https://github.com/abir614/cloudstream/security/advisories/new)**.
3. Please provide:
   - A clear description of the vulnerability.
   - Proof of Concept (PoC) or reproduction steps.
   - Potential impact on the device or application sandbox.

We will review, verify, and address reported security issues promptly.
