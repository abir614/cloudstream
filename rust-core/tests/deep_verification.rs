//! Deep-Dive Rigorous Verification Suite for CloudStream Rust Core (`cloudstream_core`).
//!
//! Thoroughly tests and benchmarks all 13 modules implemented for the project:
//! 1. Security & Anti-SSRF URL Enforcement
//! 2. Text & Multi-Vector Adult/NSFW Token Guardian
//! 3. Authenticated Argon2id + ChaCha20-Poly1305 Backup Encryption & Tamper Detection
//! 4. Ed25519 Offline Update Signature Verification
//! 5. Zero-Trust APK Identity & Attestation Token Verification
//! 6. Native LRU Cache with Adaptive DEFLATE In-Memory Compression
//! 7. SubRip (.srt) Subtitle Streaming Parser
//! 8. HLS Master Playlist & IPTV M3U/M3U8 Parser
//! 9. Dean Edwards Packed JavaScript Unpacker & Video Stream Extractor
//! 10. Streaming HTML Media & JSON State Extractor
//! 11. Fuzzy Search & Levenshtein Distance Matching (ASCII & CJK Unicode)
//! 12. High-Speed DEFLATE & ZLIB Compression
//! 13. Fast FNV-1a, SHA-256, and MD5 Hashing

use cloudstream_core::*;
use std::io::Write;

// ============================================================================
// 1. NETWORK & SSRF SECURITY VERIFICATION
// ============================================================================

#[test]
fn test_ssrf_comprehensive_matrix() {
    // Valid public endpoints
    assert!(validate_url_safety("https://api.cloudstream.cf/plugins.json").is_ok());
    assert!(validate_url_safety("http://cdn.stream.org:8080/manifest.m3u8?token=xyz").is_ok());
    assert!(validate_url_safety("https://8.8.8.8/dns-query").is_ok());
    assert!(validate_url_safety("https://1.1.1.1/dns-query").is_ok());

    // Loopback IPv4
    assert!(validate_url_safety("http://127.0.0.1/status").is_err());
    assert!(validate_url_safety("http://127.0.0.254:8000/").is_err());
    assert!(validate_url_safety("http://localhost:3000/api").is_err());
    assert!(validate_url_safety("http://router.local/admin").is_err());

    // Private LAN (RFC 1918)
    assert!(validate_url_safety("http://10.0.0.1/").is_err());
    assert!(validate_url_safety("http://172.16.0.1:80/").is_err());
    assert!(validate_url_safety("http://192.168.1.1/gateway").is_err());
    assert!(validate_url_safety("http://192.168.0.254/").is_err());

    // Cloud Metadata Services (AWS/GCP/Azure)
    assert!(validate_url_safety("http://169.254.169.254/latest/meta-data/").is_err());
    assert!(validate_url_safety("http://169.254.1.1/").is_err());

    // Unspecified & Broadcast
    assert!(validate_url_safety("http://0.0.0.0/").is_err());
    assert!(validate_url_safety("http://255.255.255.255/").is_err());

    // Unsafe URI schemes
    assert!(validate_url_safety("file:///etc/passwd").is_err());
    assert!(validate_url_safety("content://com.android.providers.telephony").is_err());
    assert!(validate_url_safety("ftp://private.host/video.mp4").is_err());
    assert!(validate_url_safety("javascript:alert(document.cookie)").is_err());
    assert!(validate_url_safety("data:text/html,<h1>XSS</h1>").is_err());
    assert!(validate_url_safety("").is_err());
    assert!(validate_url_safety("invalid-url-string").is_err());
}

#[test]
fn test_url_sanitizer_and_resolver() {
    // Strips tracking tokens while preserving critical stream identifiers
    let dirty = "https://stream.provider.to/v1/play?id=film_9981&utm_source=fb&fbclid=AQD123&utm_medium=cpc&quality=1080p#t=120";
    let clean = sanitize_url(dirty);
    assert!(clean.contains("id=film_9981"));
    assert!(clean.contains("quality=1080p"));
    assert!(!clean.contains("utm_source"));
    assert!(!clean.contains("fbclid"));
    assert!(!clean.contains("utm_medium"));

    // Empty and malformed URLs handled gracefully
    assert_eq!(sanitize_url(""), "");
    assert_eq!(sanitize_url("not-a-url"), "not-a-url");

    // Relative URL resolution
    let base = "https://media.server.com/hls/movies/2026/master.m3u8";
    assert_eq!(resolve_url(base, "segment001.ts"), "https://media.server.com/hls/movies/2026/segment001.ts");
    assert_eq!(resolve_url(base, "/subtitles/eng.srt"), "https://media.server.com/subtitles/eng.srt");
    assert_eq!(resolve_url(base, "//cdn2.server.com/audio.aac"), "https://cdn2.server.com/audio.aac");
    assert_eq!(resolve_url(base, "https://external.cdn/stream.m3u8"), "https://external.cdn/stream.m3u8");
}

// ============================================================================
// 2. TEXT & MULTI-VECTOR NSFW GUARDIAN MATRIX
// ============================================================================

#[test]
fn test_nsfw_text_and_guardian_matrix() {
    // 1. Explicit anime & hentai titles
    assert!(is_nsfw_text("High School DxD Born", "https://stream.to/ep1", None));
    assert!(is_nsfw_text("Overflow Uncensored", "https://stream.to/ep2", None));
    assert!(is_nsfw_text("Episode 1", "https://site.to/video", Some("Ecchi, Harem")));
    assert!(is_nsfw_text("Random", "https://site.to/r18/video.m3u8", None));

    // 2. Summer, Beach & Swimwear keywords (Kept in Moderate, Filtered in Extreme)
    assert!(!is_nsfw_text_with_level("Summer Beach OVA", "https://site.to/ova", None, LEVEL_MODERATE));
    assert!(is_nsfw_text_with_level("Summer Beach OVA", "https://site.to/ova", None, LEVEL_EXTREME));
    assert!(is_nsfw_text_with_level("Hot Bikini Special", "https://site.to/special", None, LEVEL_EXTREME));
    assert!(is_nsfw_text_with_level("Swimsuit Edition", "https://site.to/stream", None, LEVEL_EXTREME));

    // 3. CJK Unicode adult stems
    assert!(!is_nsfw_text_with_level("水着の彼女", "https://jp.to/v1", None, LEVEL_MODERATE));
    assert!(is_nsfw_text_with_level("水着の彼女", "https://jp.to/v1", None, LEVEL_EXTREME));
    assert!(is_nsfw_text("18禁 成年向け", "https://jp.to/v2", None));
    assert!(is_nsfw_text("エロマンガ", "https://jp.to/v3", None));

    // 4. Clean mainstream titles pass cleanly
    assert!(!is_nsfw_text("Interstellar", "https://imdb.com/title/tt0816692", Some("Sci-Fi, Adventure")));
    assert!(!is_nsfw_text("Demon Slayer: Kimetsu no Yaiba", "https://site.to/ds", Some("Action, Shounen")));
}

// ============================================================================
// 3. CRYPTOGRAPHIC BACKUP ENCRYPTION & TAMPER RESISTANCE
// ============================================================================

#[test]
fn test_backup_encryption_roundtrip_and_authentication() {
    let raw_payload = b"{\"watchlist\":[\"tt0944947\",\"tt1375666\"],\"settings\":{\"hw_accel\":true}}";
    let password = "SuperSecretStrongPassword2026!";

    // 1. Encrypt payload
    let encrypted = encrypt_backup_payload(raw_payload, password)
        .expect("Encryption should succeed with valid password");
    assert!(is_encrypted_backup(&encrypted));
    assert!(encrypted.len() > raw_payload.len());
    assert_eq!(&encrypted[0..6], b"CSENC1");

    // 2. Decrypt with correct password
    let decrypted = decrypt_backup_payload(&encrypted, password)
        .expect("Decryption should succeed with matching password");
    assert_eq!(&decrypted[..], &raw_payload[..]);

    // 3. Wrong password fails authentication immediately
    let wrong_pass_res = decrypt_backup_payload(&encrypted, "WrongPassword123!");
    assert!(wrong_pass_res.is_err(), "Decryption must fail with wrong passphrase");

    // 4. Bit-tampering in ciphertext triggers Poly1305 MAC failure
    let mut tampered = encrypted.clone();
    let last_idx = tampered.len() - 1;
    tampered[last_idx] ^= 0x01; // Flip 1 bit in tag/ciphertext
    let tamper_res = decrypt_backup_payload(&tampered, password);
    assert!(tamper_res.is_err(), "Bit-flipped ciphertext must fail authentication");

    // 5. Empty password rejected
    assert!(encrypt_backup_payload(raw_payload, "").is_err());
    assert!(encrypt_backup_payload(raw_payload, "   ").is_err());
}

// ============================================================================
// 4. ED25519 OFFLINE SIGNATURE VERIFICATION
// ============================================================================

#[test]
fn test_ed25519_manifest_signatures() {
    use ed25519_dalek::{Signer, SigningKey};

    let seed = [99u8; 32];
    let signing_key = SigningKey::from_bytes(&seed);
    let verifying_key = signing_key.verifying_key();
    let pub_hex = hex::encode(verifying_key.as_bytes());

    let manifest = "release_tag: v4.1.0\nmin_version: 120\nsha256: 7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069";
    let sig = signing_key.sign(manifest.as_bytes());
    let sig_hex = hex::encode(sig.to_bytes());

    // Valid signature
    assert!(verify_manifest_signature(manifest, &sig_hex, &pub_hex));

    // Tampered manifest
    let tampered_manifest = format!("{}\nmalicious_payload: true", manifest);
    assert!(!verify_manifest_signature(&tampered_manifest, &sig_hex, &pub_hex));

    // Tampered signature
    let mut bad_sig_hex = sig_hex.clone();
    bad_sig_hex.replace_range(0..2, "00");
    assert!(!verify_manifest_signature(manifest, &bad_sig_hex, &pub_hex));

    // Malformed hex inputs
    assert!(!verify_manifest_signature(manifest, "not_hex", &pub_hex));
    assert!(!verify_manifest_signature(manifest, &sig_hex, "short"));
}

// ============================================================================
// 5. ZERO-TRUST APK VERIFIER & ATTESTATION NONCE
// ============================================================================

#[test]
fn test_apk_verifier_and_attestation() {
    // 1. Session Nonce Generation
    let nonce1 = generate_session_nonce().expect("Nonce 1 should generate");
    let nonce2 = generate_session_nonce().expect("Nonce 2 should generate");
    assert_eq!(nonce1.len(), 64); // 256 bits in hex
    assert_eq!(nonce2.len(), 64);
    assert_ne!(nonce1, nonce2, "Subsequent nonces must be cryptographically distinct");

    // 2. Attestation Token Generation & Verification
    let pkg = "com.lagradost.cloudstream3";
    let cert = "12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0";
    let hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    let version_code = 125i64;

    let token = generate_attestation_token(&nonce1, hash, pkg, cert, version_code)
        .expect("Attestation token must generate");

    assert!(verify_attestation_token(&nonce1, &token, hash, pkg, cert, version_code));

    // Tampered parameters fail verification
    assert!(!verify_attestation_token(&nonce2, &token, hash, pkg, cert, version_code)); // Wrong nonce
    assert!(!verify_attestation_token(&nonce1, &token, "bad_hash", pkg, cert, version_code));
    assert!(!verify_attestation_token(&nonce1, &token, hash, "com.evil.app", cert, version_code));
    assert!(!verify_attestation_token(&nonce1, &token, hash, pkg, cert, 999i64)); // Wrong version

    // 3. File SHA-256 calculation
    let mut temp = tempfile::NamedTempFile::new().expect("Temp file should create");
    temp.write_all(b"CloudStream APK binary test payload").expect("Write payload");
    let file_hash = compute_file_sha256(temp.path()).expect("File hash should compute");
    assert_eq!(file_hash.len(), 64);
}

// ============================================================================
// 6. NATIVE LRU CACHE WITH ADAPTIVE DEFLATE COMPRESSION
// ============================================================================

#[test]
fn test_lru_cache_and_adaptive_compression() {
    let mut cache = LruCache::new(3);

    // Put items
    cache.put("k1".to_string(), "v1".to_string());
    cache.put("k2".to_string(), "v2".to_string());
    cache.put("k3".to_string(), "v3".to_string());

    assert_eq!(cache.get("k1"), Some("v1".to_string()));
    assert_eq!(cache.get("k2"), Some("v2".to_string()));
    assert_eq!(cache.get("k3"), Some("v3".to_string()));

    // Eviction test: accessing k1 moves it to MRU, so adding k4 should evict k2 (LRU)
    cache.get("k1"); // k1 accessed
    cache.put("k4".to_string(), "v4".to_string());

    assert_eq!(cache.get("k1"), Some("v1".to_string()));
    assert_eq!(cache.get("k4"), Some("v4".to_string()));
    assert_eq!(cache.get("k2"), None, "k2 should have been evicted as LRU");

    // Adaptive compression on large entries (>= 256 bytes)
    let large_json = "{\"stream_url\":\"https://cdn.example.com/hls/master.m3u8\",\"resolution\":\"1080p\"},".repeat(20);
    assert!(large_json.len() > 1000);

    cache.put("large_key".to_string(), large_json.clone());
    let restored = cache.get("large_key").expect("Restored string should match");
    assert_eq!(restored, large_json);

    // Clear and remove
    assert!(cache.remove("large_key"));
    assert_eq!(cache.get("large_key"), None);
}

// ============================================================================
// 7. SUBRIP (.SRT) SUBTITLE PARSER
// ============================================================================

#[test]
fn test_subrip_subtitles_parsing() {
    let srt_content = "\
1\r\n\
00:01:20,500 --> 00:01:23,100\r\n\
{\\an8}Welcome to <i>CloudStream</i>!\r\n\
\r\n\
2\r\n\
00:01:24.000 --> 00:01:26.500\r\n\
Enjoy high-speed streaming.\r\n";

    let cues = parse_srt(srt_content);
    assert_eq!(cues.len(), 2);

    // Cue 1
    assert_eq!(cues[0].start_us, 80_500_000); // (1m * 60 + 20s) * 1000 + 500 = 80,500 ms -> 80,500,000 us
    assert_eq!(cues[0].end_us, 83_100_000);
    assert_eq!(cues[0].alignment_tag, Some("{\\an8}".to_string()));
    assert_eq!(cues[0].text, "Welcome to <i>CloudStream</i>!");

    // Cue 2
    assert_eq!(cues[1].start_us, 84_000_000);
    assert_eq!(cues[1].end_us, 86_500_000);
    assert_eq!(cues[1].text, "Enjoy high-speed streaming.");
}

// ============================================================================
// 8. HLS MASTER PLAYLIST & IPTV PARSER
// ============================================================================

#[test]
fn test_m3u8_master_and_iptv_parser() {
    // 1. HLS Master Playlist
    let master = "\
#EXTM3U\n\
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n\
360p/index.m3u8\n\
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.64002a,mp4a.40.2\"\n\
1080p/index.m3u8\n\
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n\
720p/index.m3u8\n";

    let streams = parse_master_playlist(master, Some("https://cdn.example.com/hls/"));
    assert_eq!(streams.len(), 3);
    // Verified sorted by bandwidth descending (1080p first, then 720p, then 360p)
    assert_eq!(streams[0].bandwidth, 5000000);
    assert_eq!(streams[0].resolution, Some("1920x1080".to_string()));
    assert_eq!(streams[0].url, "https://cdn.example.com/hls/1080p/index.m3u8");

    assert_eq!(streams[1].bandwidth, 2500000);
    assert_eq!(streams[1].resolution, Some("1280x720".to_string()));

    assert_eq!(streams[2].bandwidth, 800000);
    assert_eq!(streams[2].resolution, Some("640x360".to_string()));

    // 2. IPTV Playlist
    let iptv = "\
#EXTM3U\n\
#EXTINF:-1 tvg-id=\"dw.en\" tvg-logo=\"http://logos.com/dw.png\" group-title=\"News\",DW News HD\n\
http://stream.dw.com/live.m3u8\n\
#EXTINF:-1 group-title=\"Cinema\",Movie Channel\n\
rtsp://movies.stream/live\n";

    let items = parse_iptv_playlist(iptv);
    assert_eq!(items.len(), 2);
    assert_eq!(items[0].name, "DW News HD");
    assert_eq!(items[0].tvg_id, Some("dw.en".to_string()));
    assert_eq!(items[0].logo, Some("http://logos.com/dw.png".to_string()));
    assert_eq!(items[0].group, Some("News".to_string()));
    assert_eq!(items[0].url, "http://stream.dw.com/live.m3u8");

    assert_eq!(items[1].name, "Movie Channel");
    assert_eq!(items[1].group, Some("Cinema".to_string()));
    assert_eq!(items[1].url, "rtsp://movies.stream/live");
}

// ============================================================================
// 9. DEAN EDWARDS UNPACKER & STREAM EXTRACTOR
// ============================================================================

#[test]
fn test_unpacker_and_stream_extractor() {
    // 1. Dean Edwards unpacker
    let packed = "}('0 1=2;',3,3,'var|x|10'.split('|'),0,{})";
    let unpacked = unpack_dean_edwards(packed);
    assert_eq!(unpacked, Some("var x=10;".to_string()));

    // 2. Stream URL extractor
    let messy_html = "Some text with <script>var s = 'https://video.host.com/stream/v1.m3u8?auth=1'; and fallback: \"http://backup.com/movie.mp4\"</script>";
    let urls = extract_stream_urls(messy_html);
    assert_eq!(urls.len(), 2);
    assert!(urls[0].contains("https://video.host.com/stream/v1.m3u8?auth=1"));
    assert!(urls[1].contains("http://backup.com/movie.mp4"));
}

// ============================================================================
// 10. HTML MEDIA LINK & SCRIPT JSON EXTRACTOR
// ============================================================================

#[test]
fn test_html_media_and_json_extraction() {
    // Media links
    let html = "\
    <html>\n\
      <body>\n\
        <iframe src=\"https://embed.vidsrc.me/embed/movie/12345\" width=\"100%\"></iframe>\n\
        <video src=\"//cdn.stream.com/raw.mp4\" type=\"video/mp4\"></video>\n\
      </body>\n\
    </html>";

    let links = extract_media_links(html);
    assert_eq!(links.len(), 2);
    assert_eq!(links[0].tag, "iframe");
    assert_eq!(links[0].url, "https://embed.vidsrc.me/embed/movie/12345");
    assert_eq!(links[1].tag, "video");
    assert_eq!(links[1].url, "https://cdn.stream.com/raw.mp4"); // Protocol-relative normalized

    // Script JSON extraction
    let script_html = "\
    <script>\n\
      var playerConfig = {\n\
        \"file\": \"https://cdn.example.com/hls.m3u8\",\n\
        \"title\": \"Example Movie\",\n\
        \"tracks\": [{\"file\": \"sub.vtt\", \"label\": \"English\"}]\n\
      };\n\
    </script>";

    let json = extract_script_json(script_html, "playerConfig");
    assert!(json.is_some());
    let parsed: serde_json::Value = serde_json::from_str(&json.unwrap()).expect("Extracted JSON must parse");
    assert_eq!(parsed["title"], "Example Movie");
    assert_eq!(parsed["file"], "https://cdn.example.com/hls.m3u8");
}

// ============================================================================
// 11. FUZZY SEARCH & LEVENSHTEIN (ASCII + CJK UNICODE)
// ============================================================================

#[test]
fn test_levenshtein_and_fuzzy_search_cjk_unicode() {
    // English & punctuation
    assert_eq!(levenshtein_distance("kitten", "sitting"), 3);
    assert_eq!(fuzzy_ratio("Breaking Bad", "breaking bad"), 100);
    assert_eq!(fuzzy_ratio("Spider-Man", "Spiderman"), 90);
    assert!(fuzzy_ratio("The Matrix", "Matrix") >= 60);

    // CJK Unicode anime titles (multibyte UTF-8 handling)
    let title1 = "鬼滅の刃";
    let title2 = "鬼滅の刃 無限列車編";
    let dist = levenshtein_distance(title1, title2);
    assert_eq!(dist, 6); // 6 extra characters: ' ', '無', '限', '列', '車', '編'
    let ratio = fuzzy_ratio(title1, title2);
    assert!((40..=60).contains(&ratio));

    // Identical CJK matches 100%
    assert_eq!(fuzzy_ratio("進撃の巨人", "進撃の巨人"), 100);
}

// ============================================================================
// 12. HIGH-SPEED DEFLATE & ZLIB COMPRESSION
// ============================================================================

#[test]
fn test_compression_engines() {
    let payload = "CloudStream Hardware Adaptive Mobile Core Native Compression 2026";
    let compressed_deflate = compress_string(payload);
    let restored_deflate = decompress_string(&compressed_deflate).expect("DEFLATE decompression");
    assert_eq!(restored_deflate, payload);

    let raw_binary = [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF].repeat(50);
    let compressed_zlib = compress_zlib(&raw_binary);
    let restored_zlib = decompress_zlib(&compressed_zlib).expect("ZLIB decompression");
    assert_eq!(&restored_zlib[..], &raw_binary[..]);

    // Corrupted bytes handling
    assert!(decompress_deflate(&[0xFF, 0xFF, 0xFF]).is_err());
    assert!(decompress_zlib(&[0x00, 0x00]).is_err());
}

// ============================================================================
// 13. FAST FNV-1A, SHA-256, AND MD5 HASHING
// ============================================================================

#[test]
fn test_cryptographic_and_fast_hashes() {
    // FNV-1a 64-bit and 32-bit
    let h1 = fast_hash64_str("cache_key_alpha");
    let h2 = fast_hash64_str("cache_key_beta");
    assert_ne!(h1, h2);
    assert_eq!(h1, fast_hash64_str("cache_key_alpha")); // Deterministic

    let h32 = fast_hash32(b"test");
    assert_ne!(h32, 0);

    // SHA-256 (NIST known answer test)
    assert_eq!(
        sha256_hex(b""),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(
        sha256_hex(b"hello world"),
        "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    );

    // MD5 (RFC 1321 known answer test)
    assert_eq!(md5_hex(b""), "d41d8cd98f00b204e9800998ecf8427e");
    assert_eq!(md5_hex(b"a"), "0cc175b9c0f1b6a831c399e269772661");
    assert_eq!(md5_hex(b"abc"), "900150983cd24fb0d6963f7d28e17f72");
    assert_eq!(
        md5_hex(b"message digest"),
        "f96b697d7cb7938d525a2f31aaf161d0"
    );
}
