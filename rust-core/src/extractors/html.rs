//! Fast streaming HTML media extractor.
//! Extracts iframes, video sources, and player configuration JSON without
//! allocating massive Java DOM tree objects on the Android heap.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExtractedMediaLink {
    pub tag: String,
    pub url: String,
    pub mime_type: Option<String>,
}

/// Extract all `<iframe src="...">`, `<video src="...">`, and `<source src="...">` tags
/// directly from raw HTML bytes in document order without DOM allocations.
pub fn extract_media_links(html: &str) -> Vec<ExtractedMediaLink> {
    let mut links = Vec::new();
    let lower = html.to_ascii_lowercase();
    let mut offset = 0;

    while offset < html.len() {
        // Find next tag occurrence among iframe, video, source
        let next_iframe = lower[offset..].find("<iframe").map(|i| (offset + i, "iframe"));
        let next_video = lower[offset..].find("<video").map(|i| (offset + i, "video"));
        let next_source = lower[offset..].find("<source").map(|i| (offset + i, "source"));

        let candidates = [next_iframe, next_video, next_source];
        let next_match = candidates
            .iter()
            .filter_map(|&c| c)
            .min_by_key(|&(idx, _)| idx);

        let (tag_start, tag_name) = match next_match {
            Some(m) => m,
            None => break,
        };

        let tag_end = match lower[tag_start..].find('>') {
            Some(end) => tag_start + end,
            None => break,
        };

        let tag_slice = &html[tag_start..=tag_end];
        if let Some(src) = extract_attribute(tag_slice, "src") {
            if !src.is_empty() && (src.starts_with("http") || src.starts_with("//")) {
                let normalized = if src.starts_with("//") {
                    format!("https:{}", src)
                } else {
                    src
                };
                let mime = extract_attribute(tag_slice, "type");
                links.push(ExtractedMediaLink {
                    tag: tag_name.to_string(),
                    url: normalized,
                    mime_type: mime,
                });
            }
        }

        offset = tag_end + 1;
    }

    links
}

/// Extracts embedded JSON payloads matching key patterns like:
/// `window.__INITIAL_STATE__ = { ... }` or `var player_data = { ... }`
pub fn extract_script_json(html: &str, variable_name: &str) -> Option<String> {
    let var_idx = html.find(variable_name)?;
    let after_var = &html[var_idx + variable_name.len()..];
    let eq_idx = after_var.find('=')?;
    if !after_var[..eq_idx].trim().is_empty() {
        return None;
    }
    let after_eq = &after_var[eq_idx + 1..].trim_start();

    // Look for opening `{` or `[`
    let json_start_offset = after_eq.find(['{', '['])?;
    let json_slice = &after_eq[json_start_offset..];
    let open_char = json_slice.chars().next()?;
    let close_char = if open_char == '{' { '}' } else { ']' };

    // Balanced brace counting
    let mut depth = 0;
    let mut in_string = false;
    let mut escaped = false;
    let mut end_idx = 0;

    for (idx, ch) in json_slice.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if ch == '\\' {
            escaped = true;
            continue;
        }
        if ch == '"' {
            in_string = !in_string;
            continue;
        }
        if !in_string {
            if ch == open_char {
                depth += 1;
            } else if ch == close_char {
                depth -= 1;
                if depth == 0 {
                    end_idx = idx + 1;
                    break;
                }
            }
        }
    }

    if end_idx > 0 {
        Some(json_slice[..end_idx].trim().to_string())
    } else {
        None
    }
}

fn extract_attribute(tag_slice: &str, attr: &str) -> Option<String> {
    let lower_tag = tag_slice.to_ascii_lowercase();
    let pattern = format!("{}=", attr.to_ascii_lowercase());
    let idx = lower_tag.find(&pattern)?;
    let val_start = idx + pattern.len();
    let rest = &tag_slice[val_start..].trim_start();
    let quote = rest.chars().next()?;

    if quote == '"' || quote == '\'' {
        let content = &rest[1..];
        let end = content.find(quote)?;
        Some(content[..end].to_string())
    } else {
        let end = rest.find([' ', '>', '\r', '\n']).unwrap_or(rest.len());
        Some(rest[..end].to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_media_links() {
        let html = r#"
            <html>
                <body>
                    <iframe width="560" height="315" src="https://embed.stream.com/e/abc123xyz" frameborder="0"></iframe>
                    <video controls src="//cdn.server.com/raw_movie.mp4"></video>
                    <source src="https://cdn.server.com/stream.m3u8" type="application/x-mpegURL">
                </body>
            </html>
        "#;

        let links = extract_media_links(html);
        assert_eq!(links.len(), 3);
        assert_eq!(links[0].tag, "iframe");
        assert_eq!(links[0].url, "https://embed.stream.com/e/abc123xyz");

        assert_eq!(links[1].tag, "video");
        assert_eq!(links[1].url, "https://cdn.server.com/raw_movie.mp4");

        assert_eq!(links[2].tag, "source");
        assert_eq!(links[2].url, "https://cdn.server.com/stream.m3u8");
        assert_eq!(links[2].mime_type.as_deref(), Some("application/x-mpegURL"));
    }

    #[test]
    fn test_extract_script_json() {
        let html = r#"
            <script>
                var playerData = {"sources": [{"file": "https://server.com/hls.m3u8", "label": "1080p"}], "title": "Example Movie"};
                console.log("Ready");
            </script>
        "#;

        let json = extract_script_json(html, "playerData");
        assert!(json.is_some());
        assert!(json.unwrap().contains("https://server.com/hls.m3u8"));
    }
}
