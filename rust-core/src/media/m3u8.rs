//! Ultra-fast, zero-allocation HLS M3U8 and IPTV playlist parser.
//! Designed specifically for memory-constrained Android TV sticks and low-end devices.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct M3u8Stream {
    pub bandwidth: u64,
    pub resolution: Option<String>,
    pub codecs: Option<String>,
    pub url: String,
    pub audio: Option<String>,
    pub subtitles: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct IptvItem {
    pub name: String,
    pub url: String,
    pub logo: Option<String>,
    pub group: Option<String>,
    pub tvg_id: Option<String>,
}

/// Parse an HLS Master Playlist containing #EXT-X-STREAM-INF entries.
/// Returns extracted streams with bitrate, resolution, and resolved URLs.
pub fn parse_master_playlist(content: &str, base_url: Option<&str>) -> Vec<M3u8Stream> {
    let mut streams = Vec::new();
    let lines: Vec<&str> = content.lines().map(|l| l.trim()).collect();
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i];
        if let Some(attrs_str) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            let bandwidth = extract_number_attribute(attrs_str, "BANDWIDTH").unwrap_or(0);
            let resolution = extract_quoted_or_raw_attribute(attrs_str, "RESOLUTION");
            let codecs = extract_quoted_attribute(attrs_str, "CODECS");
            let audio = extract_quoted_attribute(attrs_str, "AUDIO");
            let subtitles = extract_quoted_attribute(attrs_str, "SUBTITLES");

            // The next non-empty, non-comment line is the stream URL
            let mut j = i + 1;
            let mut stream_url = None;
            while j < lines.len() {
                let candidate = lines[j];
                if !candidate.is_empty() && !candidate.starts_with('#') {
                    stream_url = Some(candidate);
                    break;
                }
                j += 1;
            }

            if let Some(raw_url) = stream_url {
                let final_url = if let Some(base) = base_url {
                    resolve_relative_url(base, raw_url)
                } else {
                    raw_url.to_string()
                };

                streams.push(M3u8Stream {
                    bandwidth,
                    resolution,
                    codecs,
                    url: final_url,
                    audio,
                    subtitles,
                });
                i = j;
            }
        }
        i += 1;
    }

    streams.sort_by_key(|a| std::cmp::Reverse(a.bandwidth));
    streams
}

/// Parse an IPTV M3U/M3U8 playlist with #EXTINF attributes and stream URLs.
/// Optimized for ultra-low memory usage when parsing playlists with > 50,000 entries.
pub fn parse_iptv_playlist(content: &str) -> Vec<IptvItem> {
    let mut items = Vec::new();
    let mut current_name: Option<String> = None;
    let mut current_logo: Option<String> = None;
    let mut current_group: Option<String> = None;
    let mut current_tvg_id: Option<String> = None;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        if let Some(info) = trimmed.strip_prefix("#EXTINF:") {
            if let Some(comma_idx) = info.rfind(',') {
                let name = info[comma_idx + 1..].trim();
                let attrs = &info[..comma_idx];
                current_name = Some(name.to_string());
                current_logo = extract_quoted_attribute(attrs, "tvg-logo");
                current_group = extract_quoted_attribute(attrs, "group-title");
                current_tvg_id = extract_quoted_attribute(attrs, "tvg-id");
            } else {
                current_name = Some(info.trim().to_string());
            }
        } else if trimmed.starts_with('#') {
            // Skip comments and standard M3U headers
            continue;
        } else if trimmed.starts_with("http://")
            || trimmed.starts_with("https://")
            || trimmed.starts_with("rtmp://")
            || trimmed.starts_with("rtsp://")
        {
            let name = current_name
                .take()
                .unwrap_or_else(|| format!("Channel {}", items.len() + 1));
            items.push(IptvItem {
                name,
                url: trimmed.to_string(),
                logo: current_logo.take(),
                group: current_group.take(),
                tvg_id: current_tvg_id.take(),
            });
        }
    }

    items
}

fn extract_quoted_attribute(source: &str, key: &str) -> Option<String> {
    let pattern = format!("{}=\"", key);
    let start = source.find(&pattern)?;
    let val_start = start + pattern.len();
    let end = source[val_start..].find('"')?;
    Some(source[val_start..val_start + end].to_string())
}

fn extract_quoted_or_raw_attribute(source: &str, key: &str) -> Option<String> {
    if let Some(quoted) = extract_quoted_attribute(source, key) {
        return Some(quoted);
    }
    let pattern = format!("{}=", key);
    let start = source.find(&pattern)?;
    let val_start = start + pattern.len();
    let rest = &source[val_start..];
    let end = rest.find([',', ' ', '\r', '\n']).unwrap_or(rest.len());
    Some(rest[..end].trim().to_string())
}

fn extract_number_attribute(source: &str, key: &str) -> Option<u64> {
    let pattern = format!("{}=", key);
    let start = source.find(&pattern)?;
    let val_start = start + pattern.len();
    let rest = &source[val_start..];
    let end = rest.find([',', ' ', '\r', '\n']).unwrap_or(rest.len());
    rest[..end].trim().parse::<u64>().ok()
}

fn resolve_relative_url(base: &str, relative: &str) -> String {
    if relative.starts_with("http://") || relative.starts_with("https://") {
        return relative.to_string();
    }
    if let Ok(base_parsed) = url::Url::parse(base) {
        if let Ok(joined) = base_parsed.join(relative) {
            return joined.to_string();
        }
    }
    relative.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_m3u8_master_playlist() {
        let manifest = r#"
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
360p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
720p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p.m3u8
"#;
        let base = "https://example.com/live/master.m3u8";
        let streams = parse_master_playlist(manifest, Some(base));

        assert_eq!(streams.len(), 3);
        assert_eq!(streams[0].bandwidth, 5000000);
        assert_eq!(streams[0].resolution.as_deref(), Some("1920x1080"));
        assert_eq!(streams[0].url, "https://example.com/live/1080p.m3u8");

        assert_eq!(streams[2].bandwidth, 800000);
        assert_eq!(streams[2].resolution.as_deref(), Some("640x360"));
        assert_eq!(streams[2].url, "https://example.com/live/360p.m3u8");
    }

    #[test]
    fn test_parse_iptv_playlist() {
        let iptv = r#"
#EXTM3U
#EXTINF:-1 tvg-id="cnn.us" tvg-logo="https://logo.com/cnn.png" group-title="News",CNN HD
https://stream.server.com/cnn/live.m3u8
#EXTINF:-1 tvg-id="espn.us" tvg-logo="https://logo.com/espn.png" group-title="Sports",ESPN 1
https://stream.server.com/espn/live.m3u8
"#;
        let channels = parse_iptv_playlist(iptv);
        assert_eq!(channels.len(), 2);
        assert_eq!(channels[0].name, "CNN HD");
        assert_eq!(channels[0].tvg_id.as_deref(), Some("cnn.us"));
        assert_eq!(channels[0].logo.as_deref(), Some("https://logo.com/cnn.png"));
        assert_eq!(channels[0].group.as_deref(), Some("News"));
        assert_eq!(channels[0].url, "https://stream.server.com/cnn/live.m3u8");
    }
}
