use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use argon2::{Argon2, Algorithm, Version, Params};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum BackupCipherError {
    #[error("I/O error during random bytes generation: {0}")]
    RandomError(String),
    #[error("Key derivation error: {0}")]
    KdfError(String),
    #[error("Encryption error: {0}")]
    EncryptionError(String),
    #[error("Decryption error (bad passphrase or corrupted backup): {0}")]
    DecryptionError(String),
    #[error("Invalid backup format or magic header mismatch")]
    InvalidHeader,
}

const BACKUP_MAGIC: &[u8; 6] = b"CSENC1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;

/// Derives a 256-bit symmetric key from passphrase and salt using Argon2id.
fn derive_key(passphrase: &str, salt: &[u8]) -> Result<[u8; 32], BackupCipherError> {
    let mut key: [u8; 32] = core::array::from_fn(|_| 0);
    // Mobile-optimized Argon2id params: 19 MiB RAM, 2 iterations, 1 lane
    let params = Params::new(19 * 1024, 2, 1, Some(32))
        .map_err(|e| BackupCipherError::KdfError(e.to_string()))?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

    argon2.hash_password_into(passphrase.as_bytes(), salt, &mut key)
        .map_err(|e| BackupCipherError::KdfError(e.to_string()))?;

    Ok(key)
}

/// Encrypts raw backup bytes with a user passphrase using Argon2id + ChaCha20-Poly1305.
/// Output format: [MAGIC: 6 bytes] [SALT: 16 bytes] [NONCE: 12 bytes] [CIPHERTEXT + TAG]
pub fn encrypt_backup_payload(data: &[u8], passphrase: &str) -> Result<Vec<u8>, BackupCipherError> {
    if passphrase.trim().is_empty() {
        return Err(BackupCipherError::EncryptionError("Passphrase cannot be empty".to_string()));
    }

    let mut salt: [u8; SALT_LEN] = core::array::from_fn(|_| 0);
    getrandom::getrandom(&mut salt)
        .map_err(|e| BackupCipherError::RandomError(e.to_string()))?;

    let mut nonce_bytes: [u8; NONCE_LEN] = core::array::from_fn(|_| 0);
    getrandom::getrandom(&mut nonce_bytes)
        .map_err(|e| BackupCipherError::RandomError(e.to_string()))?;

    let key = derive_key(passphrase, &salt)?;
    let cipher = ChaCha20Poly1305::new_from_slice(&key)
        .map_err(|e| BackupCipherError::EncryptionError(e.to_string()))?;

    let nonce = Nonce::from(nonce_bytes);
    let ciphertext = cipher.encrypt(&nonce, data)
        .map_err(|e| BackupCipherError::EncryptionError(e.to_string()))?;

    let mut result = Vec::with_capacity(BACKUP_MAGIC.len() + SALT_LEN + NONCE_LEN + ciphertext.len());
    result.extend_from_slice(BACKUP_MAGIC);
    result.extend_from_slice(&salt);
    result.extend_from_slice(&nonce_bytes);
    result.extend_from_slice(&ciphertext);

    Ok(result)
}

/// Decrypts an encrypted backup blob with a user passphrase.
/// Verifies the Poly1305 authentication tag; fails immediately if passphrase is wrong or file is tampered.
pub fn decrypt_backup_payload(encrypted_blob: &[u8], passphrase: &str) -> Result<Vec<u8>, BackupCipherError> {
    let min_len = BACKUP_MAGIC.len() + SALT_LEN + NONCE_LEN + 16; // 16 bytes minimum for Poly1305 tag
    if encrypted_blob.len() < min_len {
        return Err(BackupCipherError::InvalidHeader);
    }

    if &encrypted_blob[0..BACKUP_MAGIC.len()] != BACKUP_MAGIC {
        return Err(BackupCipherError::InvalidHeader);
    }

    let salt_start = BACKUP_MAGIC.len();
    let salt_end = salt_start + SALT_LEN;
    let nonce_start = salt_end;
    let nonce_end = nonce_start + NONCE_LEN;

    let salt = &encrypted_blob[salt_start..salt_end];
    let nonce_bytes = &encrypted_blob[nonce_start..nonce_end];
    let ciphertext = &encrypted_blob[nonce_end..];

    let key = derive_key(passphrase, salt)?;
    let cipher = ChaCha20Poly1305::new_from_slice(&key)
        .map_err(|e| BackupCipherError::DecryptionError(e.to_string()))?;

    let nonce = Nonce::try_from(nonce_bytes)
        .map_err(|e| BackupCipherError::DecryptionError(e.to_string()))?;
    let plaintext = cipher.decrypt(&nonce, ciphertext)
        .map_err(|e| BackupCipherError::DecryptionError(e.to_string()))?;

    Ok(plaintext)
}

/// Checks if the payload starts with the authenticated encrypted backup magic header.
pub fn is_encrypted_backup(data: &[u8]) -> bool {
    data.len() >= BACKUP_MAGIC.len() && &data[0..BACKUP_MAGIC.len()] == BACKUP_MAGIC
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_backup_encryption_roundtrip() {
        let plaintext = b"{\"settings\":{\"theme\":\"dark\"},\"history\":[1,2,3]}";
        let password = "super_secret_user_passphrase_123";

        let encrypted = encrypt_backup_payload(plaintext, password)
            .expect("Encryption should succeed");

        assert!(is_encrypted_backup(&encrypted));
        assert_ne!(plaintext.as_slice(), encrypted.as_slice());

        let decrypted = decrypt_backup_payload(&encrypted, password)
            .expect("Decryption should succeed");

        assert_eq!(plaintext.as_slice(), decrypted.as_slice());
    }

    #[test]
    fn test_wrong_password_fails_decryption() {
        let plaintext = b"sensitive backup data";
        let encrypted = encrypt_backup_payload(plaintext, "correct_password")
            .expect("Encryption should succeed");

        let result = decrypt_backup_payload(&encrypted, "wrong_password");
        assert!(result.is_err());
    }

    #[test]
    fn test_tampered_ciphertext_fails_auth() {
        let plaintext = b"sensitive backup data";
        let mut encrypted = encrypt_backup_payload(plaintext, "password")
            .expect("Encryption should succeed");

        // Tamper with the last byte (authentication tag)
        let last_idx = encrypted.len() - 1;
        encrypted[last_idx] ^= 0x01;

        let result = decrypt_backup_payload(&encrypted, "password");
        assert!(result.is_err());
    }
}
