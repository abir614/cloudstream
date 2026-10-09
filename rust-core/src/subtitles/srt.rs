use serde::{Deserialize, Serialize};

/// High-performance, zero-overhead subtitle cue representation.
/// Avoids heavy JVM regex instances, String allocations, and ART GC thrashing.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SubtitleCue {
    pub start_us: i64,
    pub end_us: i64,
    pub text: String,
    pub alignment_tag: Option<String>,
}

/// Parse timecode string into microseconds.
/// Supported formats:
/// - "HH:MM:SS,mmm" or "HH:MM:SS.mmm"
/// - "MM:SS,mmm" or "MM:SS.mmm"
pub fn parse_timecode(s: &str) -> Option<i64> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }

    // Split time and fractional millis
    let (time_part, millis_part) = if let Some(idx) = s.rfind([',', '.']) {
        (&s[..idx], Some(&s[idx + 1..]))
    } else {
        (s, None)
    };

    let segments: Vec<&str> = time_part.split(':').collect();
    let (hours, mins, secs) = match segments.len() {
        3 => {
            let h = segments[0].trim().parse::<i64>().ok()?;
            let m = segments[1].trim().parse::<i64>().ok()?;
            let sec = segments[2].trim().parse::<i64>().ok()?;
            (h, m, sec)
        }
        2 => {
            let m = segments[0].trim().parse::<i64>().ok()?;
            let sec = segments[1].trim().parse::<i64>().ok()?;
            (0, m, sec)
        }
        _ => return None,
    };

    let millis: i64 = match millis_part {
        None => 0,
        Some(m_str) => {
            let m_str = m_str.trim();
            if m_str.is_empty() {
                0
            } else {
                match m_str.len() {
                    1 => m_str.parse::<i64>().ok()? * 100,
                    2 => m_str.parse::<i64>().ok()? * 10,
                    3 => m_str.parse::<i64>().ok()?,
                    _ => m_str[..3].parse::<i64>().ok()?,
                }
            }
        }
    };

    let total_ms = (hours * 3600 + mins * 60 + secs) * 1000 + millis;
    Some(total_ms * 1000) // Convert to microseconds
}

/// Process a single subtitle line: extracts alignment tags and strips formatting tags like `{\\...}`
fn process_line(line: &str, alignment_tag: &mut Option<String>) -> String {
    let line = line.trim();
    if !line.contains("{\\") {
        return line.to_string();
    }

    let mut result = String::with_capacity(line.len());
    let mut chars = line.char_indices().peekable();

    while let Some((idx, c)) = chars.next() {
        if c == '{' && line[idx..].starts_with("{\\") {
            // Find closing '}'
            if let Some(end_rel) = line[idx..].find('}') {
                let tag = &line[idx..=idx + end_rel];
                // Check if alignment tag like {\an1} .. {\an9}
                if alignment_tag.is_none() && tag.len() == 6 && tag.starts_with("{\\an") {
                    let d = tag.as_bytes()[4];
                    if (b'1'..=b'9').contains(&d) {
                        *alignment_tag = Some(tag.to_string());
                    }
                }
                // Skip past closing '}'
                for _ in 0..end_rel {
                    chars.next();
                }
                continue;
            }
        }
        result.push(c);
    }

    result
}

/// Parse raw SubRip (.srt) data into a list of SubtitleCues.
pub fn parse_srt(content: &str) -> Vec<SubtitleCue> {
    // Strip BOM if present
    let content = content.strip_prefix('\u{feff}').unwrap_or(content);

    let mut cues = Vec::new();
    let lines: Vec<&str> = content.lines().collect();
    let total_lines = lines.len();
    let mut i = 0;

    while i < total_lines {
        let line = lines[i].trim();
        if line.is_empty() {
            i += 1;
            continue;
        }

        // Check if current line is an index or a timing line directly
        let (start_us, end_us) = if line.contains("-->") {
            // Timing line without index
            if let Some((s, e)) = parse_timing_line(line) {
                i += 1;
                (s, e)
            } else {
                i += 1;
                continue;
            }
        } else {
            // Likely an index line (e.g. "1", "2")
            if i + 1 < total_lines && lines[i + 1].contains("-->") {
                i += 1;
                if let Some((s, e)) = parse_timing_line(lines[i].trim()) {
                    i += 1;
                    (s, e)
                } else {
                    i += 1;
                    continue;
                }
            } else {
                i += 1;
                continue;
            }
        };

        // Collect text lines until an empty line or next index/timing line
        let mut text_lines = Vec::new();
        let mut cue_alignment = None;

        while i < total_lines {
            let cur = lines[i].trim();
            if cur.is_empty() {
                break;
            }
            // Check if looking ahead at next timing line
            if i + 1 < total_lines && lines[i + 1].contains("-->") && cur.parse::<u32>().is_ok() {
                break;
            }

            let processed = process_line(cur, &mut cue_alignment);
            if !processed.is_empty() {
                text_lines.push(processed);
            }
            i += 1;
        }

        if !text_lines.is_empty() {
            cues.push(SubtitleCue {
                start_us,
                end_us,
                text: text_lines.join("<br>"),
                alignment_tag: cue_alignment,
            });
        }
    }

    cues
}

fn parse_timing_line(line: &str) -> Option<(i64, i64)> {
    let mut parts = line.split("-->");
    let start_str = parts.next()?;
    let end_part = parts.next()?;
    // End part may contain additional formatting or coordinates in some vtt/srt variants
    let end_str = end_part.split_whitespace().next()?;

    let start_us = parse_timecode(start_str)?;
    let end_us = parse_timecode(end_str)?;
    Some((start_us, end_us))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_timecode_variants() {
        assert_eq!(parse_timecode("00:01:23,456"), Some(83_456_000));
        assert_eq!(parse_timecode("00:01:23.456"), Some(83_456_000));
        assert_eq!(parse_timecode("01:23,456"), Some(83_456_000));
        assert_eq!(parse_timecode("00:00:01,5"), Some(1_500_000));
        assert_eq!(parse_timecode("00:00:01,50"), Some(1_500_000));
        assert_eq!(parse_timecode("invalid"), None);
    }

    #[test]
    fn test_parse_srt_simple() {
        let srt = r#"1
00:00:01,000 --> 00:00:04,000
Hello World!

2
00:00:05,000 --> 00:00:08,000
Second line of subtitle
And line 2
"#;
        let cues = parse_srt(srt);
        assert_eq!(cues.len(), 2);
        assert_eq!(cues[0].start_us, 1_000_000);
        assert_eq!(cues[0].end_us, 4_000_000);
        assert_eq!(cues[0].text, "Hello World!");
        assert_eq!(cues[0].alignment_tag, None);

        assert_eq!(cues[1].start_us, 5_000_000);
        assert_eq!(cues[1].end_us, 8_000_000);
        assert_eq!(cues[1].text, "Second line of subtitle<br>And line 2");
    }

    #[test]
    fn test_parse_srt_alignment_and_tags() {
        let srt = r#"1
00:00:10,000 --> 00:00:12,000
{\an8}Top-center subtitle{\pos(100,200)}
"#;
        let cues = parse_srt(srt);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].text, "Top-center subtitle");
        assert_eq!(cues[0].alignment_tag, Some("{\\an8}".to_string()));
    }

    #[test]
    fn test_parse_srt_bom_and_crlf() {
        let srt = "\u{feff}1\r\n00:00:01,000 --> 00:00:02,000\r\nTest BOM\r\n";
        let cues = parse_srt(srt);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].text, "Test BOM");
    }
}
