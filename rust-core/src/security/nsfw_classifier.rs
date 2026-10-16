//! High-Performance Multi-Level NSFW, Adult, Ecchi, & Family Safety Classification Engine.
//!
//! Provides zero-overhead token analysis and domain heuristics to filter adult content
//! across three configurable sensitivity tiers:
//! - Level 0 (LOW): Filters only hardcore explicit adult pornography and raw hentai.
//! - Level 1 (MODERATE): Default family level. Filters ecchi, swimsuits, bikinis, cleavage,
//!   lingerie, provocative/sensual poses, adult anime, and hentai.
//! - Level 2 (EXTREME): Maximum sensitivity family shield. Flags any suggestive romance,
//!   onsen/bathhouse scenes, fanservice, borderline beach/swimwear, and microkinis.

pub const LEVEL_LOW: u8 = 0;
pub const LEVEL_MODERATE: u8 = 1;
pub const LEVEL_EXTREME: u8 = 2;

/// Hardcore Adult Tokens (Active in ALL levels: Low, Moderate, Extreme).
const HARDCORE_ADULT_TOKENS: &[&str] = &[
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
    "hentai",
    "hanime",
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
    "nude",
    "nudity",
    "naked",
    "topless",
    "fella",
    "cunnilingus",
    "pegging",
    // Hardcore Franchises & Studios
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

/// Suggestive, Ecchi, Swimsuit & Anatomy Tokens (Active in MODERATE & EXTREME).
const SUGGESTIVE_ECCHI_TOKENS: &[&str] = &[
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
];

/// Extreme Sensitivity / Borderline Fanservice Tokens (Active ONLY in EXTREME).
const EXTREME_SENSITIVITY_TOKENS: &[&str] = &[
    "bathing",
    "bath",
    "hotspring",
    "hot-spring",
    "onsen",
    "harem",
    "seductive",
    "provocative",
    "fanservice",
    "fan-service",
    "waifu",
    "suggestive",
    "pantyshot",
    "microkini",
    "spank",
    "spanking",
    "bathhouse",
];

/// Hardcore Adult Multi-Word Phrases (Active in ALL levels).
const HARDCORE_ADULT_PHRASES: &[&str] = &[
    "boku no pico",
    "bible black",
    "oni chichi",
    "night shift nurses",
    "la blue girl",
    "seikatsu shuukan",
    "resort boin",
    "otome dori",
    "shoujo ramune",
];

/// Suggestive & Ecchi Franchise Multi-Word Phrases (Active in MODERATE & EXTREME).
const SUGGESTIVE_ECCHI_PHRASES: &[&str] = &[
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

/// Extreme Sensitivity Phrases (Active ONLY in EXTREME).
const EXTREME_SENSITIVITY_PHRASES: &[&str] = &[
    "hot spring",
    "fan service",
    "panty shot",
    "mixed bath",
];

/// CJK Hardcore Stems (Active in ALL levels).
const CJK_HARDCORE_STEMS: &[&str] = &[
    "成年",
    "18禁",
    "無修正",
    "裏ビデオ",
    "潮吹き",
    "乱交",
    "ポルノ",
    "パイズリ",
    "フェラ",
    "中出し",
];

/// CJK Suggestive & Ecchi Stems (Active in MODERATE & EXTREME).
const CJK_SUGGESTIVE_STEMS: &[&str] = &[
    "エロ",
    "エッチ",
    "変態",
    "巨乳",
    "爆乳",
    "水着",
    "ビキニ",
    "グラビア",
    "痴女",
    "調教",
    "下着",
    "露出",
    "同人",
    "熟女",
    "性的",
    "美乳",
    "貧乳",
    "微乳",
    "パンチラ",
    "性愛",
    "アダルト",
];

/// CJK Extreme Sensitivity Stems (Active ONLY in EXTREME).
const CJK_EXTREME_STEMS: &[&str] = &[
    "混浴",
    "温泉",
    "お風呂",
    "ハーレム",
    "お色気",
    "水着回",
    "盗撮",
];

/// Evaluates whether a single text string contains adult/NSFW markers according to sensitivity level.
fn is_text_nsfw_single(text: &str, level: u8) -> bool {
    if text.is_empty() {
        return false;
    }

    // 1. Direct CJK stem checks
    for &stem in CJK_HARDCORE_STEMS {
        if text.contains(stem) {
            return true;
        }
    }
    if level >= LEVEL_MODERATE {
        for &stem in CJK_SUGGESTIVE_STEMS {
            if text.contains(stem) {
                return true;
            }
        }
    }
    if level >= LEVEL_EXTREME {
        for &stem in CJK_EXTREME_STEMS {
            if text.contains(stem) {
                return true;
            }
        }
    }

    let lower = text.to_lowercase();

    // 2. Multi-word phrase matching
    for &phrase in HARDCORE_ADULT_PHRASES {
        if lower.contains(phrase) {
            return true;
        }
    }
    if level >= LEVEL_MODERATE {
        for &phrase in SUGGESTIVE_ECCHI_PHRASES {
            if lower.contains(phrase) {
                return true;
            }
        }
    }
    if level >= LEVEL_EXTREME {
        for &phrase in EXTREME_SENSITIVITY_PHRASES {
            if lower.contains(phrase) {
                return true;
            }
        }
    }

    // 3. Substring checks for definitive adult URL/domain markers
    if lower.contains("hentai")
        || lower.contains("hanime")
        || lower.contains("javhd")
        || lower.contains("r18")
        || lower.contains("18+")
        || lower.contains("nsfw")
        || lower.contains("uncensored")
        || lower.contains("pornhub")
        || lower.contains("xvideos")
        || lower.contains("xnxx")
    {
        return true;
    }

    if level >= LEVEL_MODERATE {
        if lower.contains("ecchi")
            || lower.contains("erotica")
            || lower.contains("bikini")
            || lower.contains("swimsuit")
            || lower.contains("swimwear")
            || lower.contains("gravure")
        {
            return true;
        }
    }

    if level >= LEVEL_EXTREME {
        if lower.contains("fanservice")
            || lower.contains("harem")
            || lower.contains("microkini")
        {
            return true;
        }
    }

    // 4. Summer / Beach / Swimsuit contextual triggers
    if level >= LEVEL_MODERATE {
        let has_summer_or_beach = lower.contains("summer") || lower.contains("beach");
        if has_summer_or_beach
            && (lower.contains("swim")
                || lower.contains("bikini")
                || lower.contains("monokini")
                || lower.contains("microkini")
                || lower.contains("lingerie")
                || lower.contains("topless")
                || lower.contains("nude")
                || lower.contains("naked")
                || lower.contains("edition")
                || (lower.contains("ova") && lower.contains("special"))
                || (lower.contains("summer") && lower.contains("beach")))
        {
            return true;
        }
    }

    if level >= LEVEL_EXTREME {
        if lower.contains("onsen")
            || lower.contains("bathhouse")
            || (lower.contains("hot") && lower.contains("spring"))
            || (lower.contains("bath") && (lower.contains("girl") || lower.contains("scene") || lower.contains("special")))
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
        for &adult_token in HARDCORE_ADULT_TOKENS {
            if clean_token == adult_token {
                return true;
            }
        }
        if level >= LEVEL_MODERATE {
            for &suggestive_token in SUGGESTIVE_ECCHI_TOKENS {
                if clean_token == suggestive_token {
                    return true;
                }
            }
        }
        if level >= LEVEL_EXTREME {
            for &extreme_token in EXTREME_SENSITIVITY_TOKENS {
                if clean_token == extreme_token {
                    return true;
                }
            }
        }
    }

    false
}

/// Classifies title, url, and optional tags with a specific sensitivity level.
pub fn is_nsfw_text_with_level(title: &str, url: &str, tags: Option<&str>, level: u8) -> bool {
    if is_text_nsfw_single(title, level) {
        return true;
    }
    if is_text_nsfw_single(url, level) {
        return true;
    }
    if let Some(t) = tags {
        if is_text_nsfw_single(t, level) {
            return true;
        }
    }
    false
}

/// Default classification (Moderate - Family Level).
pub fn is_nsfw_text(title: &str, url: &str, tags: Option<&str>) -> bool {
    is_nsfw_text_with_level(title, url, tags, LEVEL_MODERATE)
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
    fn test_text_classification_sensitivity_levels() {
        // Hardcore should trigger on LOW, MODERATE, and EXTREME
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_EXTREME));

        // Ecchi / Swimsuit should NOT trigger on LOW, but MUST trigger on MODERATE and EXTREME
        assert!(!is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_EXTREME));

        // Harem / Fanservice tag should trigger on EXTREME
        assert!(!is_nsfw_text_with_level("Ordinary Show", "https://site.org", Some("Harem"), LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Ordinary Show", "https://site.org", Some("Harem"), LEVEL_EXTREME));
    }

    #[test]
    fn test_text_classification_clean_mainstream() {
        assert!(!is_nsfw_text("Attack on Titan Season 4", "https://crunchy.com/aot-s4", Some("Action, Fantasy")));
        assert!(!is_nsfw_text("Breaking Bad", "https://netflix.com/title/123", Some("Drama, Crime")));
        assert!(!is_nsfw_text("Spider-Man: Across the Spider-Verse", "https://sony.com/spiderman", None));
    }
}
