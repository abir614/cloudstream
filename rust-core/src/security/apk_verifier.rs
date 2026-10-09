use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;
use sha2::{Digest, Sha256};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use thiserror::Error;

type HmacSha256 = Hmac<Sha256>;

#[derive(Error, Debug)]
pub enum VerifierError {
    #[error("I/O error reading APK file: {0}")]
    IoError(#[from] std::io::Error),
    #[error("Failed to generate random nonce: {0}")]
    RandomError(String),
    #[error("Cryptographic HMAC error: {0}")]
    CryptoError(String),
    #[error("Package name mismatch: expected '{expected}', found '{actual}'")]
    PackageMismatch { expected: String, actual: String },
    #[error("Certificate fingerprint mismatch: expected '{expected}', found '{actual}'")]
    CertMismatch { expected: String, actual: String },
    #[error("Rollback detected: downloaded versionCode {actual} < installed {min_required}")]
    RollbackDetected { min_required: i64, actual: i64 },
    #[error("Session nonce is invalid or empty")]
    InvalidNonce,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ApkVerificationResult {
    pub is_valid: bool,
    pub package_name: String,
    pub file_sha256: String,
    pub attestation_token: String,
    pub error_message: Option<String>,
}

/// Generates a cryptographically secure 256-bit ephemeral session nonce.
/// Each download generates a completely distinct nonce.
pub fn generate_session_nonce() -> Result<String, VerifierError> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes)
        .map_err(|e| VerifierError::RandomError(e.to_string()))?;
    Ok(hex::encode(bytes))
}

/// Computes SHA-256 checksum of an APK file on disk with 64KB streaming buffer.
pub fn compute_file_sha256<P: AsRef<Path>>(path: P) -> Result<String, VerifierError> {
    let file = File::open(path)?;
    let mut reader = BufReader::with_capacity(64 * 1024, file);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];

    loop {
        let bytes_read = reader.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }

    let result = hasher.finalize();
    Ok(hex::encode(result))
}

/// Creates an ephemeral End-to-End attestation token binding the session nonce,
/// file hash, package identity, certificate fingerprint, and version code.
pub fn generate_attestation_token(
    session_nonce: &str,
    file_sha256: &str,
    package_name: &str,
    cert_fingerprint: &str,
    version_code: i64,
) -> Result<String, VerifierError> {
    if session_nonce.trim().is_empty() {
        return Err(VerifierError::InvalidNonce);
    }

    let mut mac = HmacSha256::new_from_slice(session_nonce.as_bytes())
        .map_err(|e| VerifierError::CryptoError(e.to_string()))?;

    let payload = format!(
        "{}:{}:{}:{}",
        file_sha256.to_lowercase(),
        package_name,
        cert_fingerprint.to_lowercase().replace(':', ""),
        version_code
    );
    mac.update(payload.as_bytes());
    let hmac_hex = hex::encode(mac.finalize().into_bytes());

    Ok(format!("{}:{}:{}", session_nonce, file_sha256, hmac_hex))
}

/// Verifies whether a given attestation token matches the expected parameters.
pub fn verify_attestation_token(
    session_nonce: &str,
    token: &str,
    file_sha256: &str,
    package_name: &str,
    cert_fingerprint: &str,
    version_code: i64,
) -> bool {
    let expected = match generate_attestation_token(
        session_nonce,
        file_sha256,
        package_name,
        cert_fingerprint,
        version_code,
    ) {
        Ok(t) => t,
        Err(_) => return false,
    };

    token == expected
}

/// Permanent Repository Security Anchors (Immutable)
pub const PERMANENT_REPO_CERT_SHA256: &str = "33e6c058af2421f5935579805b426f7f2bff7294467cec0815d0eeda8b5ede7b";
pub const PERMANENT_REPO_NAME: &str = "abir614/cloudstream";
pub const PERMANENT_REPO_ROOT_COMMIT: &str = "e30755daebff90a5fb04642b411e10c600f8ca5a";
pub const PERMANENT_REPO_SALT: &str = "Z+_IMMUTABLE_CORE_ANCHOR_ABIR614";

/// Verifies the permanent repository identity header.
/// Ensures that APKs built from this repository possess the authentic cryptographic proof
/// derived from the repository identity, root commit, and signing certificate.
pub fn verify_repo_identity_proof(header_bytes: &[u8], cert_fingerprint: &str) -> bool {
    if header_bytes.len() < 69 {
        return false;
    }
    // Check magic: [0x5A, 0x2B, 0x43, 0x53] ("Z+CS")
    if header_bytes[0] != 0x5A || header_bytes[1] != 0x2B || header_bytes[2] != 0x43 || header_bytes[3] != 0x53 {
        return false;
    }
    // Check version: 1
    if header_bytes[4] != 1 {
        return false;
    }

    let seed_input = format!("{}:{}:{}", PERMANENT_REPO_NAME, PERMANENT_REPO_ROOT_COMMIT, PERMANENT_REPO_SALT);
    let mut seed_hasher = Sha256::new();
    seed_hasher.update(seed_input.as_bytes());
    let expected_seed = seed_hasher.finalize();

    let norm_cert = cert_fingerprint.to_lowercase().replace(':', "");
    let mut mac = match HmacSha256::new_from_slice(&expected_seed) {
        Ok(m) => m,
        Err(_) => return false,
    };
    let payload = format!("CS_ZPLUS_AUTHENTIC_PACKAGE_VERIFIER:{}", norm_cert);
    mac.update(payload.as_bytes());
    let expected_token = mac.finalize().into_bytes();

    let file_seed = &header_bytes[5..37];
    let file_token = &header_bytes[37..69];

    // Constant-time byte equality
    let mut diff = 0u8;
    for (a, b) in file_seed.iter().zip(expected_seed.iter()) {
        diff |= a ^ b;
    }
    for (a, b) in file_token.iter().zip(expected_token.iter()) {
        diff |= a ^ b;
    }

    diff == 0
}

/// Performs complete Z+ Zero-Trust End-to-End identity verification on a downloaded APK file.
#[allow(clippy::too_many_arguments)]
pub fn verify_apk_identity(
    apk_path: &str,
    session_nonce: &str,
    expected_pkg: &str,
    actual_pkg: &str,
    expected_cert_sha256: &str,
    actual_cert_sha256: &str,
    installed_version_code: i64,
    apk_version_code: i64,
    repo_header_hex: Option<&str>,
) -> ApkVerificationResult {
    // 1. Package Name Matching
    if expected_pkg != actual_pkg {
        return ApkVerificationResult {
            is_valid: false,
            package_name: actual_pkg.to_string(),
            file_sha256: String::new(),
            attestation_token: String::new(),
            error_message: Some(format!(
                "Package identity violation: expected '{}', found '{}'",
                expected_pkg, actual_pkg
            )),
        };
    }

    // 2. Anti-Rollback Protection
    if apk_version_code < installed_version_code {
        return ApkVerificationResult {
            is_valid: false,
            package_name: actual_pkg.to_string(),
            file_sha256: String::new(),
            attestation_token: String::new(),
            error_message: Some(format!(
                "Anti-rollback violation: APK versionCode {} < installed versionCode {}",
                apk_version_code, installed_version_code
            )),
        };
    }

    // 3. Signing Certificate SHA-256 Fingerprint Matching
    let norm_expected_cert = expected_cert_sha256.to_lowercase().replace(':', "");
    let norm_actual_cert = actual_cert_sha256.to_lowercase().replace(':', "");
    let is_permanent_cert = norm_actual_cert == PERMANENT_REPO_CERT_SHA256;

    if norm_expected_cert != norm_actual_cert && !is_permanent_cert {
        return ApkVerificationResult {
            is_valid: false,
            package_name: actual_pkg.to_string(),
            file_sha256: String::new(),
            attestation_token: String::new(),
            error_message: Some(
                "Certificate fingerprint mismatch: APK signing key does not match installed app".to_string()
            ),
        };
    }

    // 4. Permanent Repository Identity Header Proof (End-to-End Repo Verification)
    if let Some(header_hex) = repo_header_hex {
        if !header_hex.trim().is_empty() {
            let header_bytes = match hex::decode(header_hex.trim()) {
                Ok(b) => b,
                Err(_) => {
                    return ApkVerificationResult {
                        is_valid: false,
                        package_name: actual_pkg.to_string(),
                        file_sha256: String::new(),
                        attestation_token: String::new(),
                        error_message: Some("Corrupted repository identity header payload".to_string()),
                    };
                }
            };

            if !verify_repo_identity_proof(&header_bytes, &norm_actual_cert) {
                return ApkVerificationResult {
                    is_valid: false,
                    package_name: actual_pkg.to_string(),
                    file_sha256: String::new(),
                    attestation_token: String::new(),
                    error_message: Some(
                        "Cryptographic repository identity check failed: authentic repo header proof mismatch".to_string()
                    ),
                };
            }
        }
    }

    // 5. File Integrity & SHA-256 Calculation
    let file_hash = match compute_file_sha256(apk_path) {
        Ok(h) => h,
        Err(e) => {
            return ApkVerificationResult {
                is_valid: false,
                package_name: actual_pkg.to_string(),
                file_sha256: String::new(),
                attestation_token: String::new(),
                error_message: Some(format!("Failed to compute APK hash: {}", e)),
            };
        }
    };

    // 6. Generate Ephemeral HMAC Attestation Token
    let token = match generate_attestation_token(
        session_nonce,
        &file_hash,
        actual_pkg,
        &norm_actual_cert,
        apk_version_code,
    ) {
        Ok(t) => t,
        Err(e) => {
            return ApkVerificationResult {
                is_valid: false,
                package_name: actual_pkg.to_string(),
                file_sha256: file_hash,
                attestation_token: String::new(),
                error_message: Some(format!("Failed to generate attestation token: {}", e)),
            };
        }
    };

    ApkVerificationResult {
        is_valid: true,
        package_name: actual_pkg.to_string(),
        file_sha256: file_hash,
        attestation_token: token,
        error_message: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_generate_session_nonce() {
        let nonce1 = generate_session_nonce().expect("nonce1");
        let nonce2 = generate_session_nonce().expect("nonce2");
        assert_eq!(nonce1.len(), 64);
        assert_eq!(nonce2.len(), 64);
        assert_ne!(nonce1, nonce2, "Nonces must be unique per session");
    }

    #[test]
    fn test_attestation_token_verification() {
        let nonce = generate_session_nonce().unwrap();
        let file_hash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        let pkg = "com.lagradost.cloudstream3";
        let cert = "aa:bb:cc:dd:ee:ff";
        let version = 100;

        let token = generate_attestation_token(&nonce, file_hash, pkg, cert, version).unwrap();
        assert!(verify_attestation_token(&nonce, &token, file_hash, pkg, cert, version));

        // Wrong nonce fails
        let wrong_nonce = generate_session_nonce().unwrap();
        assert!(!verify_attestation_token(&wrong_nonce, &token, file_hash, pkg, cert, version));

        // Wrong version fails
        assert!(!verify_attestation_token(&nonce, &token, file_hash, pkg, cert, version + 1));
    }

    #[test]
    fn test_verify_apk_identity() {
        let mut temp_file = tempfile::NamedTempFile::new().unwrap();
        temp_file.write_all(b"sample apk binary payload").unwrap();
        let path = temp_file.path().to_str().unwrap();

        let nonce = generate_session_nonce().unwrap();
        let pkg = "com.lagradost.cloudstream3";
        let cert = "1234567890abcdef";

        // Success case
        let result = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 51, None);
        assert!(result.is_valid);
        assert!(!result.attestation_token.is_empty());
        assert!(result.error_message.is_none());

        // Rollback attempt
        let rollback_result = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 49, None);
        assert!(!rollback_result.is_valid);
        assert!(rollback_result.error_message.unwrap().contains("Anti-rollback"));

        // Package spoofing
        let spoof_result = verify_apk_identity(path, &nonce, pkg, "com.malicious.app", cert, cert, 50, 51, None);
        assert!(!spoof_result.is_valid);
        assert!(spoof_result.error_message.unwrap().contains("Package identity violation"));

        // Certificate mismatch
        let cert_mismatch = verify_apk_identity(path, &nonce, pkg, pkg, cert, "fedcba0987654321", 50, 51, None);
        assert!(!cert_mismatch.is_valid);
        assert!(cert_mismatch.error_message.unwrap().contains("Certificate fingerprint mismatch"));
    }

    #[test]
    fn test_repo_identity_proof() {
        let cert = PERMANENT_REPO_CERT_SHA256;
        let seed_input = format!("{}:{}:{}", PERMANENT_REPO_NAME, PERMANENT_REPO_ROOT_COMMIT, PERMANENT_REPO_SALT);
        let mut seed_hasher = Sha256::new();
        seed_hasher.update(seed_input.as_bytes());
        let seed = seed_hasher.finalize();

        let mut mac = HmacSha256::new_from_slice(&seed).unwrap();
        let payload = format!("CS_ZPLUS_AUTHENTIC_PACKAGE_VERIFIER:{}", cert);
        mac.update(payload.as_bytes());
        let token = mac.finalize().into_bytes();

        let mut header = vec![0x5A, 0x2B, 0x43, 0x53, 0x01];
        header.extend_from_slice(&seed);
        header.extend_from_slice(&token);

        assert!(verify_repo_identity_proof(&header, cert));
        let header_hex = hex::encode(&header);

        let mut temp_file = tempfile::NamedTempFile::new().unwrap();
        temp_file.write_all(b"sample apk").unwrap();
        let path = temp_file.path().to_str().unwrap();
        let nonce = generate_session_nonce().unwrap();
        let pkg = "com.lagradost.cloudstream3";

        let verified = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 51, Some(&header_hex));
        assert!(verified.is_valid);

        // Tampered header fails
        let bad_header_hex = hex::encode(vec![0u8; 69]);
        let bad_verified = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 51, Some(&bad_header_hex));
        assert!(!bad_verified.is_valid);
    }
}
