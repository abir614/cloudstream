use url::Url;

/// Tracking and analytics query parameters that are stripped to protect user privacy
/// and maximize HTTP / player cache hits.
const TRACKING_QUERY_PARAMS: &[&str] = &[
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_term",
    "utm_content",
    "utm_cid",
    "utm_reader",
    "utm_name",
    "fbclid",
    "gclid",
    "gclsrc",
    "msclkid",
    "mc_cid",
    "mc_eid",
    "yclid",
    "_hsenc",
    "_hsmi",
    "_openstat",
    "igshid",
    "ref",
    "referrer",
];

/// Sanitize URL by stripping tracking and analytics parameters, normalizes path,
/// and returns clean URL string.
pub fn sanitize_url(raw_url: &str) -> String {
    let raw_url = raw_url.trim();
    if raw_url.is_empty() {
        return String::new();
    }

    let mut parsed = match Url::parse(raw_url) {
        Ok(u) => u,
        Err(_) => return raw_url.to_string(),
    };

    // Only process http / https schemes
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return raw_url.to_string();
    }

    if parsed.query().is_some() {
        let pairs: Vec<(String, String)> = parsed
            .query_pairs()
            .filter(|(k, _)| !is_tracking_param(k))
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();

        if pairs.is_empty() {
            parsed.set_query(None);
        } else {
            parsed.query_pairs_mut().clear().extend_pairs(pairs.iter());
        }
    }

    parsed.to_string()
}

fn is_tracking_param(param_name: &str) -> bool {
    let lower = param_name.to_ascii_lowercase();
    TRACKING_QUERY_PARAMS.iter().any(|&p| p == lower)
}

/// Resolves a relative URL against a base URL cleanly and securely.
/// Handles:
/// - Already absolute URLs ("https://example.com/...")
/// - Protocol-relative URLs ("//cdn.example.com/...") -> "https://..."
/// - Root-relative URLs ("/videos/stream.m3u8")
/// - Relative URLs ("segment01.ts" against "https://example.com/hls/")
pub fn resolve_url(base: &str, relative: &str) -> String {
    let relative = relative.trim();
    let base = base.trim();

    if relative.starts_with("http://") || relative.starts_with("https://") {
        return relative.to_string();
    }

    if relative.starts_with("//") {
        return format!("https:{}", relative);
    }

    if base.is_empty() {
        return relative.to_string();
    }

    match Url::parse(base) {
        Ok(base_url) => match base_url.join(relative) {
            Ok(joined) => joined.to_string(),
            Err(_) => relative.to_string(),
        },
        Err(_) => {
            // Fallback string concatenation if base isn't a full URL
            if relative.starts_with('/') {
                format!("{}{}", base.trim_end_matches('/'), relative)
            } else {
                format!("{}/{}", base.trim_end_matches('/'), relative)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_url_removes_tracking() {
        let input = "https://example.com/watch?id=123&utm_source=twitter&utm_medium=social&token=abc";
        let cleaned = sanitize_url(input);
        assert_eq!(cleaned, "https://example.com/watch?id=123&token=abc");

        let input_all_tracking = "https://example.com/video?utm_source=fb&fbclid=XYZ";
        let cleaned_all = sanitize_url(input_all_tracking);
        assert_eq!(cleaned_all, "https://example.com/video");
    }

    #[test]
    fn test_sanitize_url_preserves_clean() {
        let input = "https://example.com/playlist.m3u8?sig=1234&expires=9999";
        let cleaned = sanitize_url(input);
        assert_eq!(cleaned, input);
    }

    #[test]
    fn test_resolve_url_variants() {
        let base = "https://example.com/hls/master.m3u8";
        assert_eq!(
            resolve_url(base, "720p.m3u8"),
            "https://example.com/hls/720p.m3u8"
        );
        assert_eq!(
            resolve_url(base, "/root/media.m3u8"),
            "https://example.com/root/media.m3u8"
        );
        assert_eq!(
            resolve_url(base, "//cdn.example.org/stream.mp4"),
            "https://cdn.example.org/stream.mp4"
        );
        assert_eq!(
            resolve_url(base, "https://other.com/file.ts"),
            "https://other.com/file.ts"
        );
    }
}
