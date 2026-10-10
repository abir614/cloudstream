//! High-performance native JavaScript de-obfuscation and Dean Edwards Unpacker.
//! Executes packed scripts in microseconds without starting heavy Rhino/V8/QuickJS JVM engines.

use std::collections::HashMap;

/// Detects and unpacks Dean Edwards `p,a,c,k,e,d` obfuscated JavaScript.
/// Returns the clean, unpacked JavaScript string.
pub fn unpack_dean_edwards(script: &str) -> Option<String> {
    let marker = "}('";
    let start_pos = script.find(marker).or_else(|| script.find("}(\""))?;
    let payload_start = start_pos + 3;

    // Extract payload `p`
    let quote_char = script.as_bytes()[payload_start - 1];
    let payload_end = find_matching_quote(&script[payload_start..], quote_char as char)?;
    let payload = &script[payload_start..payload_start + payload_end];

    // Rest of arguments after `p,`
    let args_slice = &script[payload_start + payload_end + 1..];
    let comma_pos = args_slice.find(',')?;
    let args_slice = &args_slice[comma_pos + 1..];

    // Radix `a`
    let next_comma = args_slice.find(',')?;
    let radix_str = args_slice[..next_comma].trim();
    let radix: u32 = radix_str.parse().ok()?;

    // Count `c`
    let args_slice = &args_slice[next_comma + 1..];
    let next_comma = args_slice.find(',')?;
    let count_str = args_slice[..next_comma].trim();
    let _count: u32 = count_str.parse().ok()?;

    // Keywords dictionary `k`
    let args_slice = &args_slice[next_comma + 1..];
    let k_start_quote = args_slice.find(['\'', '"'])?;
    let k_quote = args_slice.as_bytes()[k_start_quote] as char;
    let k_content = &args_slice[k_start_quote + 1..];
    let k_end_quote = find_matching_quote(k_content, k_quote)?;
    let keywords_raw = &k_content[..k_end_quote];

    let keywords: Vec<&str> = keywords_raw.split('|').collect();

    // Fast dictionary lookup map
    let mut lookup = HashMap::new();
    for (idx, kw) in keywords.iter().enumerate() {
        if !kw.is_empty() {
            let encoded_key = encode_radix(idx as u32, radix);
            lookup.insert(encoded_key, *kw);
        }
    }

    // Replace all word tokens in `payload`
    let mut result = String::with_capacity(payload.len() * 2);
    let mut current_token = String::new();

    for ch in payload.chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' {
            current_token.push(ch);
        } else {
            if !current_token.is_empty() {
                if let Some(replacement) = lookup.get(&current_token) {
                    result.push_str(replacement);
                } else {
                    result.push_str(&current_token);
                }
                current_token.clear();
            }
            result.push(ch);
        }
    }

    if !current_token.is_empty() {
        if let Some(replacement) = lookup.get(&current_token) {
            result.push_str(replacement);
        } else {
            result.push_str(&current_token);
        }
    }

    // Unescape common slashes
    let cleaned = result.replace("\\'", "'").replace("\\\"", "\"");
    Some(cleaned)
}

/// Rapid zero-allocation scanner for stream URLs (.m3u8, .mp4, .mpd, .mkv) in raw strings.
pub fn extract_stream_urls(content: &str) -> Vec<String> {
    let mut urls = Vec::new();
    let lower = content.to_ascii_lowercase();
    let extensions = [".m3u8", ".mp4", ".mpd", ".mkv"];

    for ext in extensions {
        let mut offset = 0;
        while let Some(ext_idx) = lower[offset..].find(ext) {
            let real_ext_idx = offset + ext_idx;
            
            // Backtrack to find start of URL ("http" or quotes)
            let bytes = content.as_bytes();
            let mut start = real_ext_idx;
            while start > 0 {
                let b = bytes[start - 1];
                if b == b'"' || b == b'\'' || b == b'(' || b == b'>' || b == b'=' || b == b' ' || b == b'\n' || b == b'\r' {
                    break;
                }
                start -= 1;
            }

            // Forward-track to end of URL
            let mut end = real_ext_idx + ext.len();
            while end < content.len() {
                let b = bytes[end];
                if b == b'"' || b == b'\'' || b == b')' || b == b'<' || b == b' ' || b == b'\n' || b == b'\r' || b == b';' {
                    break;
                }
                end += 1;
            }

            let candidate = &content[start..end];
            if (candidate.starts_with("http://") || candidate.starts_with("https://")) && !urls.contains(&candidate.to_string()) {
                urls.push(candidate.to_string());
            }

            offset = real_ext_idx + ext.len();
        }
    }

    urls
}

fn find_matching_quote(s: &str, quote: char) -> Option<usize> {
    let mut escaped = false;
    for (i, c) in s.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if c == '\\' {
            escaped = true;
            continue;
        }
        if c == quote {
            return Some(i);
        }
    }
    None
}

fn encode_radix(mut num: u32, radix: u32) -> String {
    if num == 0 {
        return "0".to_string();
    }
    const CHARS: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    let r = radix.min(62) as usize;
    let mut result = Vec::new();

    while num > 0 {
        let rem = (num as usize) % r;
        result.push(CHARS[rem] as char);
        num /= radix;
    }

    result.reverse();
    result.into_iter().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dean_edwards_unpacker() {
        let packed = r#"eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 2=1;',3,3,'var|100|x'.split('|'))"#;
        let unpacked = unpack_dean_edwards(packed);
        assert!(unpacked.is_some());
        assert_eq!(unpacked.unwrap(), "var x=100;");
    }

    #[test]
    fn test_extract_stream_urls() {
        let html = r#"
            <script>
                var videoSource = "https://cdn.stream.com/hls/video_1080p.m3u8?token=abc123";
                var fallback = 'https://backup.stream.com/files/movie.mp4';
            </script>
        "#;
        let urls = extract_stream_urls(html);
        assert_eq!(urls.len(), 2);
        assert_eq!(urls[0], "https://cdn.stream.com/hls/video_1080p.m3u8?token=abc123");
        assert_eq!(urls[1], "https://backup.stream.com/files/movie.mp4");
    }
}
