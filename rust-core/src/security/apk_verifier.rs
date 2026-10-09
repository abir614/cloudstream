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
    if norm_expected_cert != norm_actual_cert {
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

    // 4. File Integrity & SHA-256 Calculation
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

    // 5. Generate Ephemeral HMAC Attestation Token
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
        let result = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 51);
        assert!(result.is_valid);
        assert!(!result.attestation_token.is_empty());
        assert!(result.error_message.is_none());

        // Rollback attempt
        let rollback_result = verify_apk_identity(path, &nonce, pkg, pkg, cert, cert, 50, 49);
        assert!(!rollback_result.is_valid);
        assert!(rollback_result.error_message.unwrap().contains("Anti-rollback"));

        // Package spoofing
        let spoof_result = verify_apk_identity(path, &nonce, pkg, "com.malicious.app", cert, cert, 50, 51);
        assert!(!spoof_result.is_valid);
        assert!(spoof_result.error_message.unwrap().contains("Package identity violation"));

        // Certificate mismatch
        let cert_mismatch = verify_apk_identity(path, &nonce, pkg, pkg, cert, "fedcba0987654321", 50, 51);
        assert!(!cert_mismatch.is_valid);
        assert!(cert_mismatch.error_message.unwrap().contains("Certificate fingerprint mismatch"));
    }
}
