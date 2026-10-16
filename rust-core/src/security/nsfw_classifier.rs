//! High-Performance NSFW, Adult, Ecchi, & Summer/Beach Content Classification Engine.
//!
//! Provides zero-overhead token analysis and domain heuristics to filter adult content
//! including hentai blended into mainstream anime streams, ecchi feeds, and swimsuit/beach themes.

pub use crate::security::poster_detector::is_skin_exposure_excessive;

/// Static list of explicit adult, ecchi, and swimsuit tokens (matched as whole words/tokens).
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
    "+18",
    "18+",
    "18plus",
    "erotica",
    "erotic",
    // Hentai & Anime Adult Terminology
    "hentai",
    "hanime",
    "ecchi",
    "ero-anime",
    "eroanime",
    "eroge",
    "ero",
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
    "decensored",
    "smut",
    "lewd",
    "lust",
    "sensual",
    "sex",
    "sexy",
    "yuri",
    "yaoi",
    "playboy",
    "softcore",
    "hardcore",
    // Anatomy & Swimwear / Beach / Summer exposure
    "bikini",
    "swimsuit",
    "swimwear",
    "beachwear",
    "monokini",
    "gravure",
    "mizugi",
    "lingerie",
    "thong",
    "panties",
    "panty",
    "nude",
    "nudity",
    "naked",
    "topless",
    "cleavage",
    "oppai",
    "boob",
    "boobs",
    "breasts",
    "busty",
    "bakunyuu",
    "kyonyuu",
    "milf",
    "bbw",
    "bdsm",
    "fetish",
    "bondage",
    "peeping",
    "voyeur",
    "chijo",
    // Specific Notorious Hentai Franchises & Stems
    "rance",
    "kuroinu",
    "taimanin",
    "mankitsu",
    "overflow",
    "euphoria",
    "futabu",
    "shoujoramune",
    "otomedori",
    "resortboin",
    "onichichi",
    "seikatsushuukan",
    "fella",
    "cunnilingus",
    "pegging",
    // Adult Studios & Platforms
    "bunnywalker",
    "pinkpineapple",
    "queenbee",
    "poros",
    "greenbunny",
    "whitebear",
    "naturalhigh",
    "nhentai",
    "tsumino",
    "fakku",
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

/// Multi-word adult / hentai franchise phrases (matched as case-insensitive substrings).
const EXPLICIT_ADULT_PHRASES: &[&str] = &[
    "boku no pico",
    "bible black",
    "oni chichi",
    "night shift nurses",
    "la blue girl",
    "seikatsu shuukan",
    "resort boin",
    "otome dori",
    "shoujo ramune",
    "high school dxd",
    "shinmai maou",
    "yosuga no sora",
    "aki sora",
    "redo of healer",
    "interspecies reviewers",
    "valkyrie drive",
    "prison school",
    "kiss x sis",
    "to love-ru",
    "to love ru",
    "seikon no qwaser",
];

/// CJK Adult & Swimsuit Stems (Japanese Kanji / Katakana / Hiragana, Chinese).
const CJK_ADULT_STEMS: &[&str] = &[
    "エロ",
    "エッチ",
    "変態",
    "成年",
    "18禁",
    "無修正",
    "巨乳",
    "爆乳",
    "水着",
    "ビキニ",
    "グラビア",
    "痴女",
    "調教",
    "下着",
    "露出",
    "海辺",
    "水着回",
    "同人",
    "裏ビデオ",
    "盗撮",
    "熟女",
    "潮吹き",
    "乱交",
    "アダルト",
    "ポルノ",
    "パイズリ",
    "パンチラ",
    "フェラ",
    "性愛",
    "性的",
    "美乳",
    "貧乳",
    "微乳",
    "中出し",
];

/// Evaluates whether a single text string contains adult, ecchi, hentai, or summer/beach swimsuit markers.
fn is_text_nsfw_single(text: &str) -> bool {
    if text.is_empty() {
        return false;
    }

    // 1. Direct CJK substring matching
    for &cjk_stem in CJK_ADULT_STEMS {
        if text.contains(cjk_stem) {
            return true;
        }
    }

    let lower = text.to_lowercase();

    // 2. Multi-word phrase matching
    for &phrase in EXPLICIT_ADULT_PHRASES {
        if lower.contains(phrase) {
            return true;
        }
    }

    // 3. Substring checks for definitive adult URL/domain markers
    if lower.contains("hentai")
        || lower.contains("hanime")
        || lower.contains("javhd")
        || lower.contains("ecchi")
        || lower.contains("erotica")
        || lower.contains("bikini")
        || lower.contains("swimsuit")
        || lower.contains("swimwear")
        || lower.contains("gravure")
        || lower.contains("r18")
        || lower.contains("18+")
        || lower.contains("nsfw")
        || lower.contains("uncensored")
    {
        return true;
    }

    // 4. Summer / Beach contextual triggers:
    // If text contains "summer" or "beach", combined with provocative/lewd markers
    let has_summer_or_beach = lower.contains("summer") || lower.contains("beach");
    if has_summer_or_beach {
        if lower.contains("swim")
            || lower.contains("pool")
            || lower.contains("bikini")
            || lower.contains("hot")
            || lower.contains("sun")
            || lower.contains("vacation")
            || lower.contains("special")
            || lower.contains("ova")
            || lower.contains("edition")
            || lower.contains("island")
            || lower.contains("girl")
            || lower.contains("girls")
        {
            return true;
        }
    }

    // 5. Delimited token matching (words separated by whitespace, dashes, slashes, etc.)
    let tokens = lower.split(|c: char| {
        c.is_whitespace()
            || c == '-'
            || c == '_'
            || c == '/'
            || c == '.'
            || c == ','
            || c == ':'
            || c == ';'
            || c == '?'
            || c == '&'
            || c == '='
            || c == '['
            || c == ']'
            || c == '('
            || c == ')'
            || c == '{'
            || c == '}'
            || c == '+'
    });

    for token in tokens {
        let clean_token = token.trim();
        if clean_token.is_empty() {
            continue;
        }
        for &adult_token in EXPLICIT_ADULT_TOKENS {
            if clean_token == adult_token {
                return true;
            }
        }
    }

    false
}

/// Classifies title, url, and optional tags to identify adult/NSFW/ecchi/summer-swimsuit media.
pub fn is_nsfw_text(title: &str, url: &str, tags: Option<&str>) -> bool {
    if is_text_nsfw_single(title) {
        return true;
    }
    if is_text_nsfw_single(url) {
        return true;
    }
    if let Some(t) = tags {
        if is_text_nsfw_single(t) {
            return true;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_text_classification_explicit_and_ecchi() {
        assert!(is_nsfw_text("High School DxD Season 1", "https://anime.tv/hsdxd", None));
        assert!(is_nsfw_text("Clean Title", "https://hentaistream.org/watch/123", None));
        assert!(is_nsfw_text("Anime Episode 1", "https://cdn.net/ep1.mp4", Some("Ecchi, Harem, Romance")));
        assert!(is_nsfw_text("Random Show", "https://provider.to/xxx/stream", None));
        assert!(is_nsfw_text("Boku no Pico OVA", "https://cdn.me/video.mp4", None));
    }

    #[test]
    fn test_text_classification_beach_and_summer() {
        assert!(is_nsfw_text("Summer Beach Special OVA", "https://stream.to/v1", None));
        assert!(is_nsfw_text("Hot Spring & Bikini Vacation", "https://stream.to/v2", None));
        assert!(is_nsfw_text("Girl in Swimsuit", "https://stream.to/v3", None));
        assert!(is_nsfw_text("Island Summer Edition", "https://stream.to/v4", None));
    }

    #[test]
    fn test_text_classification_cjk_stems() {
        assert!(is_nsfw_text("水着の美少女", "https://jp.anime/play", None));
        assert!(is_nsfw_text("18禁 成年コミック アニメ化", "https://jp.anime/play", None));
        assert!(is_nsfw_text("ビキニグラビア", "https://jp.anime/play", None));
        assert!(is_nsfw_text("エロマンガ先生", "https://jp.anime/play", None));
    }

    #[test]
    fn test_text_classification_clean_mainstream() {
        assert!(!is_nsfw_text("Attack on Titan Season 4", "https://crunchy.com/aot-s4", Some("Action, Fantasy")));
        assert!(!is_nsfw_text("Breaking Bad", "https://netflix.com/title/123", Some("Drama, Crime")));
        assert!(!is_nsfw_text("Spider-Man: Across the Spider-Verse", "https://sony.com/spiderman", None));
    }

    #[test]
    fn test_poster_math_delegation() {
        let mut nsfw_image = Vec::with_capacity(3000);
        let flesh_palette = [
            [255, 220, 198],
            [245, 205, 180],
            [230, 185, 155],
            [255, 195, 185],
            [220, 170, 145],
        ];

        for i in 0..1000 {
            let color = flesh_palette[i % flesh_palette.len()];
            nsfw_image.extend_from_slice(&color);
        }

        assert!(is_skin_exposure_excessive(&nsfw_image, 3));
    }

    #[test]
    fn test_clean_image_delegation() {
        let mut clean_image = Vec::with_capacity(3000);
        for _ in 0..1000 {
            clean_image.extend_from_slice(&[40, 100, 200]);
        }

        assert!(!is_skin_exposure_excessive(&clean_image, 3));
    }
}
