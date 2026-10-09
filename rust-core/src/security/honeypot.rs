use sha2::{Digest, Sha256};
use std::sync::Mutex;
use std::time::Duration;

/// Canary patterns that indicate malicious host probing or sandbox escape attempts.
const CANARY_TRAPS: &[&str] = &[
    "/proc/self/maps",
    "/proc/self/mem",
    "/proc/version",
    "/etc/passwd",
    "/etc/shadow",
    "c:\\windows\\system32",
    "powershell.exe",
    "cmd.exe",
    "hklm\\",
    "hkey_local_machine",
    "/data/data/com.lagradost.cloudstream3/databases",
    "id_rsa",
    ".aws/credentials",
    "aws_secret_access_key",
    "../..",
    "..\\..",
];

/// A single intrusion record digested into the honeypot audit trail.
#[derive(Debug, Clone, serde::Serialize)]
pub struct IntrusionRecord {
    pub probe_pattern: String,
    pub payload_sha256: String,
    pub trap_hit_count: u32,
    pub rolling_digest: String,
}

/// Global moving-target defense engine.
pub struct HoneypotEngine {
    intrusion_count: u32,
    rolling_digest: [u8; 32],
    records: Vec<IntrusionRecord>,
}

impl Default for HoneypotEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl HoneypotEngine {
    pub fn new() -> Self {
        Self {
            intrusion_count: 0,
            rolling_digest: [0u8; 32],
            records: Vec::with_capacity(32),
        }
    }

    /// Evaluates a candidate path or payload. If it trips a canary trap,
    /// triggers execution hesitation, returns hallucinated decoy data,
    /// and digests the intrusion into the tamper-evident chain.
    pub fn evaluate_probe(&mut self, candidate_path: &str, candidate_payload: &str) -> Option<String> {
        let lower_path = candidate_path.to_ascii_lowercase();
        let lower_payload = candidate_payload.to_ascii_lowercase();

        let hit_trap = CANARY_TRAPS.iter().find(|&&trap| {
            lower_path.contains(trap) || lower_payload.contains(trap)
        });

        if let Some(&trap) = hit_trap {
            // 1. Asymmetric hesitation (tarpit delay) to stall malware
            std::thread::sleep(Duration::from_millis(50));

            // 2. Digest intrusion into rolling hash
            self.digest_intrusion(trap, candidate_payload);

            // 3. Return hallucinated synthetic data
            Some(generate_hallucinated_decoy(trap))
        } else {
            None
        }
    }

    fn digest_intrusion(&mut self, trap: &str, payload: &str) {
        self.intrusion_count = self.intrusion_count.saturating_add(1);

        let mut payload_hasher = Sha256::new();
        payload_hasher.update(payload.as_bytes());
        let payload_sha256 = hex::encode(payload_hasher.finalize());

        let mut chain_hasher = Sha256::new();
        chain_hasher.update(self.rolling_digest);
        chain_hasher.update(trap.as_bytes());
        chain_hasher.update(payload_sha256.as_bytes());
        chain_hasher.update(self.intrusion_count.to_le_bytes());
        self.rolling_digest = chain_hasher.finalize().into();

        if self.records.len() < 50 {
            self.records.push(IntrusionRecord {
                probe_pattern: trap.to_string(),
                payload_sha256,
                trap_hit_count: self.intrusion_count,
                rolling_digest: hex::encode(self.rolling_digest),
            });
        }
    }

    pub fn get_audit_digest(&self) -> String {
        hex::encode(self.rolling_digest)
    }

    pub fn get_intrusion_count(&self) -> u32 {
        self.intrusion_count
    }
}

/// Generates realistic synthetic responses that cause probing malware to hallucinate
/// and waste execution cycles without reaching the host operating system.
fn generate_hallucinated_decoy(trap: &str) -> String {
    match trap {
        "/etc/passwd" | "/etc/shadow" => {
            "root:x:0:0:root:/root:/bin/false\nnobody:x:65534:65534:nobody:/nonexistent:/bin/false\n".to_string()
        }
        "/proc/self/maps" | "/proc/self/mem" => {
            "00400000-00452000 r-xp 00000000 08:02 173521 /opt/cloudstream/bin/isolated_runner\n".to_string()
        }
        "cmd.exe" | "powershell.exe" | "c:\\windows\\system32" => {
            "Access Denied: Capability Token 0xDEADBEEF required for host subsystem dispatch.\n".to_string()
        }
        _ => {
            format!(
                "{{\"status\":\"isolated\",\"env\":\"cloudstream-secure-sandbox\",\"entropy\":\"{}\"}}",
                hex::encode(Sha256::digest(trap.as_bytes()))
            )
        }
    }
}

// Global thread-safe Honeypot Engine
static HONEYPOT_INSTANCE: Mutex<Option<HoneypotEngine>> = Mutex::new(None);

pub fn evaluate_honeypot_probe(path: &str, payload: &str) -> Option<String> {
    let mut lock = HONEYPOT_INSTANCE.lock().ok()?;
    let engine = lock.get_or_insert_with(HoneypotEngine::new);
    engine.evaluate_probe(path, payload)
}

pub fn get_honeypot_audit_digest() -> String {
    if let Ok(mut lock) = HONEYPOT_INSTANCE.lock() {
        let engine = lock.get_or_insert_with(HoneypotEngine::new);
        engine.get_audit_digest()
    } else {
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_honeypot_clean_path() {
        let mut engine = HoneypotEngine::new();
        assert_eq!(engine.evaluate_probe("https://example.com/stream.m3u8", ""), None);
        assert_eq!(engine.get_intrusion_count(), 0);
    }

    #[test]
    fn test_honeypot_trap_triggered_and_digested() {
        let mut engine = HoneypotEngine::new();
        let decoy = engine.evaluate_probe("/etc/passwd", "probe_test");
        assert!(decoy.is_some());
        assert!(decoy.unwrap().contains("root:x:0:0"));
        assert_eq!(engine.get_intrusion_count(), 1);
        assert!(!engine.get_audit_digest().is_empty());
    }

    #[test]
    fn test_honeypot_windows_trap() {
        let mut engine = HoneypotEngine::new();
        let decoy = engine.evaluate_probe("C:\\Windows\\System32\\cmd.exe", "exec");
        assert!(decoy.is_some());
        assert!(decoy.unwrap().contains("Capability Token"));
    }
}
