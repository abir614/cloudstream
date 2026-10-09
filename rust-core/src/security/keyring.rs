use sha2::Sha256;
use hmac::{Hmac, Mac};
use std::sync::RwLock;

type HmacSha256 = Hmac<Sha256>;

/// Key slots for domain-separated cryptographic isolation.
#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeySlot {
    RootSigning = 0,
    SessionCipher = 1,
    StorageHmac = 2,
    CanarySalt = 3,
    HoneypotEntropy = 4,
}

impl KeySlot {
    pub fn from_u32(val: u32) -> Option<Self> {
        match val {
            0 => Some(KeySlot::RootSigning),
            1 => Some(KeySlot::SessionCipher),
            2 => Some(KeySlot::StorageHmac),
            3 => Some(KeySlot::CanarySalt),
            4 => Some(KeySlot::HoneypotEntropy),
            _ => None,
        }
    }
}

/// A tamper-evident cryptographic key-ring providing multi-layer domain separation.
/// Designed for ultra-low memory footprints (under 1KB heap footprint) with zero JVM GC pressure.
pub struct SecureKeyRing {
    master_seed: [u8; 32],
    epoch: u64,
}

impl SecureKeyRing {
    /// Initialize keyring with deterministic or random entropy
    pub fn new(seed: Option<[u8; 32]>) -> Self {
        let master_seed = match seed {
            Some(s) => s,
            None => {
                let mut buf = [0u8; 32];
                let _ = getrandom::getrandom(&mut buf);
                buf
            }
        };
        Self {
            master_seed,
            epoch: 1,
        }
    }

    /// Derives an isolated key for a specific slot and context using HKDF-style HMAC expansion.
    pub fn derive_slot_key(&self, slot: KeySlot, context: &[u8]) -> [u8; 32] {
        let mut mac = HmacSha256::new_from_slice(&self.master_seed)
            .expect("HMAC can take key of any size");
        mac.update(&(slot as u32).to_le_bytes());
        mac.update(&self.epoch.to_le_bytes());
        mac.update(context);
        let result = mac.finalize().into_bytes();
        let mut key = [0u8; 32];
        key.copy_from_slice(&result[..32]);
        key
    }

    /// Issues a cryptographic proof token for a given payload and slot
    pub fn issue_token(&self, slot: KeySlot, payload: &[u8]) -> String {
        let slot_key = self.derive_slot_key(slot, b"cloudstream_token_domain");
        let mut mac = HmacSha256::new_from_slice(&slot_key)
            .expect("HMAC can take key of any size");
        mac.update(payload);
        hex::encode(mac.finalize().into_bytes())
    }

    /// Verifies a cryptographic proof token against payload and slot
    pub fn verify_token(&self, slot: KeySlot, payload: &[u8], expected_hex: &str) -> bool {
        let expected_bytes = match hex::decode(expected_hex) {
            Ok(b) => b,
            Err(_) => return false,
        };
        let slot_key = self.derive_slot_key(slot, b"cloudstream_token_domain");
        let mut mac = HmacSha256::new_from_slice(&slot_key)
            .expect("HMAC can take key of any size");
        mac.update(payload);
        mac.verify_slice(&expected_bytes).is_ok()
    }

    /// Advance epoch to invalidate old ephemeral tokens
    pub fn rotate_epoch(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
    }
}

// Global thread-safe keyring instance
static GLOBAL_KEYRING: RwLock<Option<SecureKeyRing>> = RwLock::new(None);

pub fn init_keyring(seed: Option<[u8; 32]>) {
    let mut lock = GLOBAL_KEYRING.write().expect("lock keyring");
    *lock = Some(SecureKeyRing::new(seed));
}

pub fn get_keyring_token(slot: KeySlot, payload: &[u8]) -> String {
    let mut lock = GLOBAL_KEYRING.write().expect("lock keyring");
    let kr = lock.get_or_insert_with(|| SecureKeyRing::new(None));
    kr.issue_token(slot, payload)
}

pub fn verify_keyring_token(slot: KeySlot, payload: &[u8], token: &str) -> bool {
    let lock = GLOBAL_KEYRING.read().expect("lock keyring");
    if let Some(kr) = lock.as_ref() {
        kr.verify_token(slot, payload, token)
    } else {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_keyring_derivation_isolation() {
        let kr = SecureKeyRing::new(Some([0x42; 32]));
        let k1 = kr.derive_slot_key(KeySlot::RootSigning, b"ctx1");
        let k2 = kr.derive_slot_key(KeySlot::SessionCipher, b"ctx1");
        let k3 = kr.derive_slot_key(KeySlot::RootSigning, b"ctx2");
        assert_ne!(k1, k2);
        assert_ne!(k1, k3);
    }

    #[test]
    fn test_token_issue_and_verification() {
        let kr = SecureKeyRing::new(Some([0x99; 32]));
        let token = kr.issue_token(KeySlot::StorageHmac, b"video_watch_progress_item_1");
        assert!(kr.verify_token(KeySlot::StorageHmac, b"video_watch_progress_item_1", &token));
        assert!(!kr.verify_token(KeySlot::StorageHmac, b"tampered_data", &token));
        assert!(!kr.verify_token(KeySlot::RootSigning, b"video_watch_progress_item_1", &token));
    }
}
