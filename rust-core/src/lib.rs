pub mod cache;
pub mod crypto;
pub mod extractors;
pub mod jni_bridge;
pub mod media;
pub mod network;
pub mod sandbox;
pub mod search;
pub mod security;
pub mod subtitles;

pub use cache::{cache_clear, cache_get, cache_put, cache_remove, LruCache};
pub use crypto::{fast_hash32, fast_hash64, fast_hash64_str, md5_hex, sha256_hex};
pub use extractors::html::{extract_media_links, extract_script_json, ExtractedMediaLink};
pub use extractors::unpacker::{extract_stream_urls, unpack_dean_edwards};
pub use media::m3u8::{parse_iptv_playlist, parse_master_playlist, IptvItem, M3u8Stream};
pub use network::{resolve_url, sanitize_url};
pub use sandbox::wasm::{SandboxError, WasmSandbox};
pub use search::levenshtein::{fuzzy_ratio, levenshtein_distance};
pub use security::apk_verifier::{
    compute_file_sha256, generate_attestation_token, generate_session_nonce, verify_apk_identity,
    verify_attestation_token, ApkVerificationResult, VerifierError,
};
pub use security::ssrf::{validate_url_safety, NetworkSecurityError};
pub use subtitles::{parse_srt, SubtitleCue};
