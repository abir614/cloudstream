use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum Ed25519Error {
    #[error("Invalid public key length or format: {0}")]
    InvalidPublicKey(String),
    #[error("Invalid signature length or format: {0}")]
    InvalidSignature(String),
    #[error("Verification failed: signature does not match message")]
    VerificationFailed,
}

/// Verifies an Ed25519 signature against raw message bytes.
pub fn verify_ed25519_signature(
    public_key_bytes: &[u8],
    message: &[u8],
    signature_bytes: &[u8],
) -> Result<bool, Ed25519Error> {
    if public_key_bytes.len() != 32 {
        return Err(Ed25519Error::InvalidPublicKey("Key must be 32 bytes".to_string()));
    }
    if signature_bytes.len() != 64 {
        return Err(Ed25519Error::InvalidSignature("Signature must be 64 bytes".to_string()));
    }

    let key_array: [u8; 32] = public_key_bytes.try_into()
        .map_err(|_| Ed25519Error::InvalidPublicKey("Failed to read 32 bytes".to_string()))?;

    let verifying_key = VerifyingKey::from_bytes(&key_array)
        .map_err(|e| Ed25519Error::InvalidPublicKey(e.to_string()))?;

    let sig_array: [u8; 64] = signature_bytes.try_into()
        .map_err(|_| Ed25519Error::InvalidSignature("Failed to read 64 bytes".to_string()))?;

    let signature = Signature::from_bytes(&sig_array);

    match verifying_key.verify(message, &signature) {
        Ok(()) => Ok(true),
        Err(_) => Ok(false),
    }
}

/// Verifies a hex-encoded Ed25519 signature of an update manifest string against a hex-encoded public key.
pub fn verify_manifest_signature(
    manifest_content: &str,
    signature_hex: &str,
    public_key_hex: &str,
) -> bool {
    let clean_sig = signature_hex.trim();
    let clean_pk = public_key_hex.trim();

    let sig_bytes = match hex::decode(clean_sig) {
        Ok(b) => b,
        Err(_) => return false,
    };

    let pk_bytes = match hex::decode(clean_pk) {
        Ok(b) => b,
        Err(_) => return false,
    };

    verify_ed25519_signature(&pk_bytes, manifest_content.as_bytes(), &sig_bytes).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    #[test]
    fn test_valid_ed25519_signature() {
        let mut seed = [0u8; 32];
        seed[0] = 42;
        let signing_key = SigningKey::from_bytes(&seed);
        let verifying_key = signing_key.verifying_key();

        let message = b"CloudStream-v4.0.0-release.apk SHA256: 0123456789abcdef";
        let signature = signing_key.sign(message);

        let is_valid = verify_ed25519_signature(
            verifying_key.as_bytes(),
            message,
            &signature.to_bytes(),
        ).expect("verification should run");

        assert!(is_valid);
    }

    #[test]
    fn test_tampered_message_fails() {
        let mut seed = [0u8; 32];
        seed[0] = 77;
        let signing_key = SigningKey::from_bytes(&seed);
        let verifying_key = signing_key.verifying_key();

        let message = b"original release manifest";
        let tampered = b"tampered release manifest";
        let signature = signing_key.sign(message);

        let is_valid = verify_ed25519_signature(
            verifying_key.as_bytes(),
            tampered,
            &signature.to_bytes(),
        ).expect("verification should run");

        assert!(!is_valid);
    }

    #[test]
    fn test_hex_manifest_signature_verification() {
        let mut seed = [0u8; 32];
        seed[0] = 99;
        let signing_key = SigningKey::from_bytes(&seed);
        let verifying_key = signing_key.verifying_key();

        let manifest = "SHA256SUMS\n5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8  app.apk";
        let signature = signing_key.sign(manifest.as_bytes());

        let sig_hex = hex::encode(signature.to_bytes());
        let pk_hex = hex::encode(verifying_key.as_bytes());

        assert!(verify_manifest_signature(manifest, &sig_hex, &pk_hex));
        assert!(!verify_manifest_signature("tampered manifest", &sig_hex, &pk_hex));
    }
}
