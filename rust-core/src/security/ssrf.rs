use std::net::IpAddr;
use url::Url;
use thiserror::Error;

#[derive(Error, Debug, PartialEq, Eq)]
pub enum NetworkSecurityError {
    #[error("Invalid URL format: {0}")]
    InvalidUrl(String),
    #[error("Blocked URL scheme: '{0}'. Only 'http' and 'https' are permitted.")]
    BlockedScheme(String),
    #[error("Local, loopback, or private network access is blocked (SSRF Protection): {0}")]
    PrivateNetworkBlocked(String),
    #[error("Cloud metadata endpoints are blocked: {0}")]
    CloudMetadataBlocked(String),
}

/// Validates whether a requested URL is safe for an untrusted extension to access.
/// Prevents Server-Side Request Forgery (SSRF) against LAN routers, local services,
/// and metadata endpoints.
pub fn validate_url_safety(raw_url: &str) -> Result<Url, NetworkSecurityError> {
    let parsed = Url::parse(raw_url)
        .map_err(|e| NetworkSecurityError::InvalidUrl(e.to_string()))?;

    // Rule 1: Strict Scheme Whitelist
    match parsed.scheme() {
        "http" | "https" => {}
        scheme => return Err(NetworkSecurityError::BlockedScheme(scheme.to_string())),
    }

    // Rule 2: Host must exist
    let host_str = parsed.host_str().ok_or_else(|| {
        NetworkSecurityError::InvalidUrl("Missing host in URL".to_string())
    })?;

    // Rule 3: Check for localhost hostnames
    let lower_host = host_str.to_lowercase();
    if lower_host == "localhost" || lower_host.ends_with(".localhost") || lower_host.ends_with(".local") {
        return Err(NetworkSecurityError::PrivateNetworkBlocked(lower_host));
    }

    // Rule 4: If host parses as IP address, enforce strict IP safety
    if let Ok(ip) = host_str.parse::<IpAddr>() {
        validate_ip_safety(&ip)?;
    }

    Ok(parsed)
}

/// Ensures an IP address does not point to loopback, private RFC-1918, link-local,
/// broadcast, or cloud metadata ranges.
pub fn validate_ip_safety(ip: &IpAddr) -> Result<(), NetworkSecurityError> {
    match ip {
        IpAddr::V4(ipv4) => {
            // Loopback (127.0.0.0/8)
            if ipv4.is_loopback() {
                return Err(NetworkSecurityError::PrivateNetworkBlocked(ip.to_string()));
            }
            // Private RFC-1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            if ipv4.is_private() {
                return Err(NetworkSecurityError::PrivateNetworkBlocked(ip.to_string()));
            }
            // Link-local: 169.254.0.0/16 (includes AWS/GCP/Azure metadata 169.254.169.254)
            if ipv4.is_link_local() {
                return Err(NetworkSecurityError::CloudMetadataBlocked(ip.to_string()));
            }
            // Broadcast (255.255.255.255) & Unspecified (0.0.0.0)
            if ipv4.is_broadcast() || ipv4.is_unspecified() {
                return Err(NetworkSecurityError::PrivateNetworkBlocked(ip.to_string()));
            }
        }
        IpAddr::V6(ipv6) => {
            if ipv6.is_loopback() || ipv6.is_unspecified() {
                return Err(NetworkSecurityError::PrivateNetworkBlocked(ip.to_string()));
            }
            // IPv6 Unique Local (fc00::/7) or Link-local (fe80::/10)
            let segments = ipv6.segments();
            if (segments[0] & 0xfe00) == 0xfc00 || (segments[0] & 0xffc0) == 0xfe80 {
                return Err(NetworkSecurityError::PrivateNetworkBlocked(ip.to_string()));
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_public_urls() {
        assert!(validate_url_safety("https://api.example.com/stream").is_ok());
        assert!(validate_url_safety("http://cdn.mysite.org:8080/playlist.m3u8").is_ok());
    }

    #[test]
    fn test_blocked_schemes() {
        assert_eq!(
            validate_url_safety("file:///etc/passwd"),
            Err(NetworkSecurityError::BlockedScheme("file".to_string()))
        );
        assert_eq!(
            validate_url_safety("content://com.android.providers.media/"),
            Err(NetworkSecurityError::BlockedScheme("content".to_string()))
        );
        assert_eq!(
            validate_url_safety("javascript:alert(1)"),
            Err(NetworkSecurityError::BlockedScheme("javascript".to_string()))
        );
    }

    #[test]
    fn test_blocked_private_ips() {
        assert!(validate_url_safety("http://127.0.0.1/admin").is_err());
        assert!(validate_url_safety("http://192.168.1.1/setup").is_err());
        assert!(validate_url_safety("http://10.0.0.5/api").is_err());
        assert!(validate_url_safety("http://172.16.0.1/secret").is_err());
        assert!(validate_url_safety("http://localhost:8080/test").is_err());
    }

    #[test]
    fn test_blocked_cloud_metadata() {
        assert_eq!(
            validate_url_safety("http://169.254.169.254/latest/meta-data/"),
            Err(NetworkSecurityError::CloudMetadataBlocked("169.254.169.254".to_string()))
        );
    }
}
