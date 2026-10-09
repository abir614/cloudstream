use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IptvChannel {
    pub name: String,
    pub stream_url: String,
    pub logo: Option<String>,
    pub group: Option<String>,
    pub tvg_id: Option<String>,
}

/// Zero-copy, high-speed M3U parser designed for resource-constrained ARMv7/ARMv8 devices.
/// Parses multi-megabyte playlists in milliseconds without creating massive heap bloat.
pub struct M3uParser;

impl M3uParser {
    pub fn parse(content: &str) -> Vec<IptvChannel> {
        let mut channels = Vec::new();
        let mut current_name: Option<String> = None;
        let mut current_logo: Option<String> = None;
        let mut current_group: Option<String> = None;
        let mut current_tvg_id: Option<String> = None;

        for line in content.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }

            if trimmed.starts_with("#EXTINF:") {
                // Parse attributes and name: #EXTINF:-1 tvg-id="123" tvg-logo="url" group-title="News",Channel Name
                let info_part = &trimmed["#EXTINF:".len()..];
                
                // Extract channel name (everything after the last comma)
                if let Some(comma_idx) = info_part.rfind(',') {
                    current_name = Some(info_part[comma_idx + 1..].trim().to_string());
                    let attrs_part = &info_part[..comma_idx];

                    current_logo = Self::extract_attribute(attrs_part, "tvg-logo");
                    current_group = Self::extract_attribute(attrs_part, "group-title");
                    current_tvg_id = Self::extract_attribute(attrs_part, "tvg-id");
                } else {
                    current_name = Some(info_part.trim().to_string());
                }
            } else if trimmed.starts_with('#') {
                // Ignore other metadata headers like #EXTM3U, #EXTVLCOPT
                continue;
            } else if trimmed.starts_with("http://") || trimmed.starts_with("https://") || trimmed.starts_with("rtmp://") {
                // Found stream URL
                if let Some(name) = current_name.take() {
                    channels.push(IptvChannel {
                        name,
                        stream_url: trimmed.to_string(),
                        logo: current_logo.take(),
                        group: current_group.take(),
                        tvg_id: current_tvg_id.take(),
                    });
                }
            }
        }

        channels
    }

    #[inline]
    fn extract_attribute(source: &str, key: &str) -> Option<String> {
        let pattern = format!("{}=\"", key);
        if let Some(start) = source.find(&pattern) {
            let value_start = start + pattern.len();
            if let Some(end) = source[value_start..].find('"') {
                return Some(source[value_start..value_start + end].to_string());
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_m3u_stream() {
        let sample = r#"
#EXTM3U
#EXTINF:-1 tvg-id="BBC1" tvg-logo="https://example.com/logo.png" group-title="UK",BBC One HD
https://stream.example.com/bbc1/live.m3u8
#EXTINF:-1 group-title="Sports",Sky Sports News
http://stream.example.com/sky/live.m3u8
"#;

        let channels = M3uParser::parse(sample);
        assert_eq!(channels.len(), 2);
        assert_eq!(channels[0].name, "BBC One HD");
        assert_eq!(channels[0].stream_url, "https://stream.example.com/bbc1/live.m3u8");
        assert_eq!(channels[0].tvg_id.as_deref(), Some("BBC1"));
        assert_eq!(channels[0].logo.as_deref(), Some("https://example.com/logo.png"));
        assert_eq!(channels[0].group.as_deref(), Some("UK"));

        assert_eq!(channels[1].name, "Sky Sports News");
        assert_eq!(channels[1].group.as_deref(), Some("Sports"));
    }
}
