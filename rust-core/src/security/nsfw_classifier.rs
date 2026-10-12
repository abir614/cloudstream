//! High-Performance NSFW & Hentai Content Classification Engine.
//! Provides zero-overhead token analysis and domain heuristics to filter adult content
//! including hentai blended into mainstream anime streams.

/// Static list of explicit adult / hentai tokens (matched as whole words/tokens).
const EXPLICIT_ADULT_TOKENS: &[&str] = &[
    // Core Adult Keywords
    "xxx",
    "porn",
    "porno",
    "pornography",
    "nsfw",
    "jav",
    "r18",
    "r-18",
    "r18+",
    "erotica",
    "erotic",
    // Hentai & Anime Adult Terminology
    "hentai",
    "hanime",
    "ero-anime",
    "eroanime",
    "eroge",
    "ahegao",
    "doujin",
    "doujinshi",
    "futanari",
    "netorare",
    "ntr",
    "chikan",
    "paizuri",
    "nakadashi",
    "shibari",
    "bukkake",
    "creampie",
    "blowjob",
    "gangbang",
    "deepthroat",
    "swinger",
    "cuckold",
    "masturbation",
    "incest",
    "uncensored",
    // Studio / Platform Markers
    "bunnywalker",
    "pinkpineapple",
    "queenbee",
    "nhentai",
    "tsumino",
    "fakku",
    "luscious",
    "pornhub",
    "xvideos",
    "xnxx",
    "spankbang",
    "eporner",
    "missav",
    "supjav",
    "javhd",
    "rule34",
];

/// Known adult domain substrings in URLs
const ADULT_DOMAINS: &[&str] = &[
    "hanime.tv",
    "hentaihaven",
    "nhentai.net",
    "fakku.net",
    "tsumino.com",
    "luscious.net",
    "pornhub.com",
    "xvideos.com",
    "xnxx.com",
    "spankbang.com",
    "eporner.com",
    "missav.com",
    "supjav.com",
    "javhd.com",
    "rule34.xxx",
    "rule34video.com",
    "erome.com",
    "h-anime.net",
    "javmost",
    "javfree",
];

/// Normalizes text into lowercase tokens split by non-alphanumeric characters.
fn extract_tokens(input: &str) -> Vec<String> {
    input
        .to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect()
}

/// Checks whether an input string (title, URL, or tag list) contains NSFW or Hentai markers.
pub fn is_nsfw_text(title: &str, url: &str, tags: Option<&str>) -> bool {
    let lower_title = title.to_lowercase();
    let lower_url = url.to_lowercase();

    // 1. Check known adult domains in URL
    for domain in ADULT_DOMAINS {
        if lower_url.contains(domain) {
            return true;
        }
    }

    // Direct check for explicit markers with symbols
    if lower_title.contains("r-18") || lower_title.contains("r18+") || lower_title.contains("18+") {
        return true;
    }
    if lower_url.contains("r-18") || lower_url.contains("r18+") {
        return true;
    }

    // 2. Tokenize title and check against explicit adult/hentai tokens
    let title_tokens = extract_tokens(&lower_title);
    for token in &title_tokens {
        for adult_token in EXPLICIT_ADULT_TOKENS {
            if token == adult_token {
                return true;
            }
        }
    }

    // 3. Tokenize URL slug segments (often contains /hentai/ or /xxx/)
    let url_tokens = extract_tokens(&lower_url);
    for token in &url_tokens {
        if token == "hentai"
            || token == "hanime"
            || token == "jav"
            || token == "xxx"
            || token == "porn"
            || token == "r18"
            || token == "nsfw"
            || token == "doujin"
            || token == "futanari"
            || token == "uncensored"
            || token == "netorare"
            || token == "ntr"
        {
            return true;
        }
    }

    // 4. Check tags or genres if provided
    if let Some(tag_str) = tags {
        let lower_tags = tag_str.to_lowercase();
        if lower_tags.contains("18+") || lower_tags.contains("r-18") || lower_tags.contains("r18+") {
            return true;
        }

        let tag_tokens = extract_tokens(&lower_tags);
        for token in &tag_tokens {
            if token == "hentai"
                || token == "rx"
                || token == "erotica"
                || token == "erotic"
                || token == "adult"
                || token == "r18"
                || token == "nsfw"
                || token == "smut"
                || (token == "ecchi" && lower_title.contains("18+"))
            {
                return true;
            }
        }
    }

    false
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_detects_hentai_blended_into_anime() {
        assert!(is_nsfw_text("Overflow (Uncensored)", "https://anime.tv/watch/overflow", None));
        assert!(is_nsfw_text("Mankitsu Happening", "https://anime.tv/watch/mankitsu-happening", Some("Hentai, OVA")));
        assert!(is_nsfw_text("Ane wa Yanmama Junyuuchuu Episode 1", "https://anime.tv/watch/ane-wa-yanmama-hentai", None));
        assert!(is_nsfw_text("Rance 01: Hikari o Motomete The Animation", "https://anime.tv/watch/rance", Some("Action, Fantasy, Hentai")));
        assert!(is_nsfw_text("Some Anime Title", "https://hanime.tv/videos/hentai/video-1", None));
    }

    #[test]
    fn test_permits_clean_mainstream_anime_without_false_positives() {
        assert!(!is_nsfw_text("Attack on Titan", "https://anime.tv/watch/attack-on-titan", Some("Action, Shounen")));
        assert!(!is_nsfw_text("Hunter x Hunter (2011)", "https://anime.tv/watch/hunter-x-hunter-2011", Some("Adventure, Shounen")));
        assert!(!is_nsfw_text("Assassination Classroom", "https://anime.tv/watch/assassination-classroom", Some("Action, Comedy")));
        assert!(!is_nsfw_text("Haikyuu!! Second Season", "https://anime.tv/watch/haikyuu", Some("Sports")));
        assert!(!is_nsfw_text("Bleach: Thousand-Year Blood War", "https://anime.tv/watch/bleach-tybw", Some("Action, Supernatural")));
        assert!(!is_nsfw_text("Classroom of the Elite", "https://anime.tv/watch/classroom-of-the-elite", Some("Drama, Mystery")));
        assert!(!is_nsfw_text("Spy x Family", "https://anime.tv/watch/spy-x-family", Some("Comedy, Action")));
    }

    #[test]
    fn test_detects_general_adult_and_jav() {
        assert!(is_nsfw_text("JAV Uncensored Collection", "https://stream.com/watch/jav-123", None));
        assert!(is_nsfw_text("Hot Erotica Night", "https://stream.com/movie/hot-erotica", None));
        assert!(is_nsfw_text("Random Title", "https://spankbang.com/watch/video", None));
        assert!(is_nsfw_text("Title with adult rating", "https://stream.com/watch/title", Some("Drama, 18+, Adult")));
    }
}
