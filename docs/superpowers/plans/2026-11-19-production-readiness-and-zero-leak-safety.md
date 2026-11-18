# Universal Android Production Readiness & Zero-Leak Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish 100% production-ready stability, smoothness, and zero-leak adult content filtering across universal Android hardware tiers, from 500MB low-RAM TV boxes to flagship phones.

**Architecture:** Hardware-adaptive workload partitioning strictly isolates heavy preloading to low-end devices to prevent Out-Of-Memory and Low-Memory-Killer aborts, while mid and flagship devices utilize lazy on-demand loading. Content safety employs a multi-layered defense-in-depth barrier spanning provider domain inspection, text token parsing, on-device quantized neural inference, and perceptual chrominance analysis.

**Tech Stack:** Kotlin 2.1, Android SDK (API 23–35), Coil 3, TensorFlow Lite (quantized MobileNetV2), Coroutines/Channels, Leanback TV UI, JUnit 4.

**Spec:** Codebase audit checklist and requirements defined in `PROJECT_END_TO_END_AUDIT_CHECKLIST.md` and `FORK_CHANGES.md`.

## Global Constraints

- **Commit Short Hash Invariant**: Every commit short hash MUST strictly contain zero `'f'`s, prioritizing `'0'` or `'5'`.
- **Monotonic Versioning**: Dynamically derived from `git rev-list --count HEAD`.
- **Private Files**: Untracked files in `~/Documents/` (`worker.js`, `worker.min.js`) must never be modified or tracked.
- **Evidence Before Assertions**: Every claim of fix or pass must be backed by live execution logs or CI workflow runs.

## Review Focus

1. **Context Nullability on Low-RAM Hardware**: `CloudStreamApp.context` returning null under GC pressure must default to strict adult filtering (`isNsfwEnabled == false`), never leaking adult content.
2. **Disguised Adult Provider Screening**: Providers declaring `TvType.Anime` while serving adult content (e.g. Hanime, NHentai) must be detected via name and URL heuristics.
3. **Hardware-Adaptive Workload Partitioning**: Mid and Flagship devices must NOT preload all offscreen posters during startup; low-end devices must preload all to avoid post-render crashes.
4. **Leanback Focus Stability**: Card hiding in TV grids must never use `0x0` dimensions that cause `IllegalStateException` or break D-pad navigation.
5. **Anime Cel-Shading Visual Classification**: Pale or cel-shaded anime illustrations must not bypass classification or be falsely cached as safe (`0.0f`).

---

## Tasks

### Task 1: Comprehensive Defense-in-Depth NSFW Filter Verification
- [x] Infallible context fallback: `CloudStreamApp.appContext` permanent anchor prevents null dereferences during GC sweeps.
- [x] Strict default safety: `HomeViewModel.kt:394` defaults `nsfwEnabled` to `false` when context is null.
- [x] Provider domain screening: `NsfwFilterEngine.isNsfwProvider` inspects provider name and main URL with `isNsfwText`.
- [x] Neural inference preservation: `OnDeviceNsfwClassifier.isDeviceUnderMemoryPressure` calibrated so 5MB TFLite model executes on low-end hardware without premature bypass.
- [x] Cel-shaded anime tone detection: `isSkinTone` and `analyzeFastMetrics` calibrated for pale and stylized anime illustrations.
- [x] Elimination of false-positive safe score caching on image network failures or timeouts.
- [x] Add unit test in `NsfwFilterEngineTest.kt` verifying `isNsfwProvider` detects adult providers regardless of declared `TvType`.

### Task 2: Hardware-Adaptive Poster Partitioning & Smoothness
- [x] Low-End Devices (`budget.isLowEnd || DeviceProfile.isLowEnd`): Preload 100% of posters across all rails during the foreground loading screen in `RGB_565`, eliminate background prefetching, prune empty rails, sanitize hero previews, and release TFLite sessions before landing.
- [x] Mid & Flagship Devices: Preload only initial visible viewport (banners + first 4–8 rail posters), allowing loading screen to finish instantly; all remaining posters load lazily on demand via Coil.
- [x] TV D-pad focus safety: ViewPager2 carousel and Leanback grid cells blank out without invalidating layout geometry or breaking focus navigation.

### Task 3: End-to-End Audit & Verification of Remaining UI Screens
- [x] Verify `QuickSearchFragment.kt` applies `NsfwFilterEngine.filterSearchResults` across all search result feeds.
- [x] Verify `LibraryViewModel.kt` filters synced library items against `NsfwFilterEngine.isNsfwText` when adult content is disabled.
- [x] Enforce adult screening on TMDB search suggestions (`SearchSuggestionApi.kt`) with `include_adult` parameter and title filtration.
- [x] Enforce adult screening on search history in `SearchViewModel.kt` (`updateHistory`).
- [x] Enforce adult screening on home feed bookmarks and continue-watching in `HomeViewModel.kt`.
- [x] Guard `ResultViewModel2.kt:load()` against direct adult provider or adult title entry when NSFW is disabled.
- [x] Censor adult downloaded posters in `DownloadAdapter.kt`.
- [x] Run full project audit checklist verification across all tracked files.

### Task 4: CI Build & Deployment Verification
- [x] Monitor GitHub Actions CI pipeline for Build #369 (`5d82b01`).
- [x] Confirm 100% green passing status across Pre-release, Security Audit, CodeQL, and Dependency Submission for Build #369.
- [x] Verify live Telegram telemetry feed (`https://t.me/s/cloudstreamdata`) for zero new crash reports (Post #1213 verified from Build #366).
- [ ] Monitor GitHub Actions CI pipeline for Build #370.
