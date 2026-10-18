//! High-Performance Multi-Level NSFW, Adult, Ecchi, & Family Safety Classification Engine.
//!
//! Provides zero-overhead token analysis and domain heuristics to filter adult content
//! across three configurable sensitivity tiers:
//! - Level 0 (LOW): Hardened Moderate sensitivity. Filters explicit adult content, ecchi, smut, lewd, and suggestive content.
//! - Level 1 (MODERATE): Hardened Extreme sensitivity. Filters beach/summer/swimwear, half-body exposed, onsen, and fanservice.
//! - Level 2 (EXTREME): Super Extreme peak shield. Filters provocative adult romance tropes, erotic themes, and suggestive tags.

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

/// Suggestive, Ecchi, Swimsuit & Anatomy Tokens (Active in ALL levels - LOW hardened to Moderate).
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
    "dxd",
];

/// Extreme Sensitivity / Borderline Fanservice Tokens (Active in MODERATE & EXTREME).
const EXTREME_SENSITIVITY_TOKENS: &[&str] = &[
    "beach",
    "summer",
    "bikini",
    "swimsuit",
    "swimwear",
    "beachwear",
    "monokini",
    "microkini",
    "mizugi",
    "pool",
    "swimming",
    "halfbody",
    "half-body",
    "exposed",
    "topless",
    "bare",
    "bareback",
    "barechested",
    "underboob",
    "sideboob",
    "gravure",
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
    "panty-shot",
    "spank",
    "spanking",
    "bathhouse",
];

/// Super Extreme Sensitivity / Adult & Provocative Romance Tropes (Active ONLY in EXTREME).
const SUPER_EXTREME_TOKENS: &[&str] = &[
    "succubus",
    "incubus",
    "peeping",
    "voyeur",
    "voyeurism",
    "exhibitionism",
    "upskirt",
    "downblouse",
    "nudist",
    "nudism",
    "naturist",
    "naturism",
    "bunnygirl",
    "crossdress",
    "crossdressing",
    "femboy",
    "otokonoko",
    "concubine",
    "courtesan",
    "brothel",
    "prostitute",
    "prostitution",
    "escort",
    "callgirl",
    "redlight",
    "soapland",
    "aphrodisiac",
    "seductress",
    "temptress",
    "mistress",
    "nightwear",
    "sleepwear",
    "babydoll",
    "negligee",
    "corset",
    "fishnet",
    "stockings",
    "garter",
    "nukige",
    "galge",
    "tentacle",
    "tentacles",
    "pantsu",
    "monmusu",
    "tsugumomo",
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

/// Suggestive & Ecchi Franchise Multi-Word Phrases (Active in ALL levels - LOW hardened to Moderate).
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

/// Extreme Sensitivity Phrases (Active in MODERATE & EXTREME).
const EXTREME_SENSITIVITY_PHRASES: &[&str] = &[
    "hot spring",
    "fan service",
    "panty shot",
    "mixed bath",
];

/// Super Extreme Adult Franchise Multi-Word Phrases (Active ONLY in EXTREME).
const SUPER_EXTREME_PHRASES: &[&str] = &[
    "domestic girlfriend",
    "domestic na kanojo",
    "kuzu no honkai",
    "scum's wish",
    "valkyrie drive",
    "masou gakuen",
    "hybrid x heart",
    "manyuu hikenchou",
    "ladies versus butlers",
    "prison school",
    "shimoneta",
    "seikon no qwaser",
    "to love ru darkness",
    "motto to love ru",
    "peter grill",
    "monster musume",
    "ishuzoku reviewers",
    "ayane de battle",
    "kiss x sis",
    "kissxsis",
    "erotic romance",
    "sensual romance",
    "massage parlor",
    "adult video",
    "mature content",
    "adult anime",
    "18+ anime",
    "eromanga sensei",
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

/// CJK Suggestive & Ecchi Stems (Active in ALL levels - LOW hardened to Moderate).
const CJK_SUGGESTIVE_STEMS: &[&str] = &[
    "エロ",
    "エッチ",
    "変態",
    "巨乳",
    "爆乳",
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

/// CJK Extreme Sensitivity Stems (Active in MODERATE & EXTREME).
const CJK_EXTREME_STEMS: &[&str] = &[
    "水着",
    "ビキニ",
    "グラビア",
    "混浴",
    "温泉",
    "お風呂",
    "ハーレム",
    "お色気",
    "水着回",
    "盗撮",
];

/// CJK Super Extreme Stems (Active ONLY in EXTREME).
const CJK_SUPER_EXTREME_STEMS: &[&str] = &[
    "裏垢", "パパ活", "援助交際", "援交", "買春", "売春", "風俗", "ソープ", "ピンサロ", "セクキャバ",
    "デリヘル", "出会い系", "官能", "夜這い", "近親相姦", "義母", "義妹", "義姉", "義父", "催眠",
    "触手", "露出狂", "女体盛り", "媚薬", "肉感", "巨尻", "美尻", "谷間", "パンスト", "下着姿",
    "半裸", "全裸", "裸体", "肢体", "艶", "秘部", "水着姿", "エロアニメ", "エロ動画",
];

/// Hardcore Adult Substring Stems (Active in ALL levels).
const HARDCORE_STEMS: &[&str] = &[
    "hentai", "hanime", "porn", "porno", "xxx", "r18", "+18", "18+", "eroti", "bukkake",
    "nakadashi", "paizuri", "chikan", "shibari", "creampie", "blowjob", "deepthroat", "swinger",
    "cuckold", "cunnilingus", "pegging", "gangbang", "incest", "stepmom", "stepmother",
    "stepsister", "stepdaughter", "stepson", "stepbrother", "nudity", "topless",
    "uncensor", "decensor", "rule34", "onlyfans", "fansly", "striptease", "stripper", "camgirl",
    "playboy", "playmate", "nhentai", "tsumino", "spankbang", "eporner", "missav", "supjav", "javhd",
];

/// Suggestive & Ecchi Substring Stems (Active in ALL levels - LOW hardened to Moderate).
const SUGGESTIVE_STEMS: &[&str] = &[
    "ecchi", "ero-anime", "eroanime", "eroge", "ahegao", "doujin", "futanari", "netorare",
    "smut", "lewd", "oppai", "boob", "busty", "lingerie", "milf", "thong", "panty", "panties",
    "fetish", "bdsm", "bondage", "sensual", "chijo", "eromanga", "dxd",
];

/// Extreme Sensitivity Substring Stems (Active in MODERATE & EXTREME).
const EXTREME_STEMS: &[&str] = &[
    "summer", "beach", "swim", "bikini", "monokini", "microkini", "mizugi", "halfbody",
    "half-body", "half body", "exposed", "topless", "bareback", "barechested",
    "underboob", "sideboob", "cleavage", "onsen", "bathhouse", "hotspring", "harem",
    "fanservice", "fan-service", "waifu", "pantyshot", "panty-shot", "spank", "seductive", "provocative",
];

/// Super Extreme Substring Stems (Active ONLY in EXTREME).
const SUPER_EXTREME_STEMS: &[&str] = &[
    "succubus", "incubus", "peeping", "voyeur", "upskirt", "downblouse", "nudis", "naturis",
    "bunnygirl", "bunny-girl", "bunny girl", "crossdress", "femboy", "otokonoko", "concubine",
    "courtesan", "brothel", "prostitut", "callgirl", "redlight", "soapland", "aphrodisiac",
    "seductress", "temptress", "negligee", "babydoll", "fishnet", "nukige", "galge", "tentacle",
    "pantsu", "monmusu", "tsugumomo", "step-sister", "step-brother", "step-mother", "step-father",
    "step-daughter", "step-son",
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
    // Suggestive CJK stems active in ALL levels (LOW hardened to Moderate)
    for &stem in CJK_SUGGESTIVE_STEMS {
        if text.contains(stem) {
            return true;
        }
    }
    // Extreme CJK stems active in MODERATE & EXTREME
    if level >= LEVEL_MODERATE {
        for &stem in CJK_EXTREME_STEMS {
            if text.contains(stem) {
                return true;
            }
        }
    }
    // Super Extreme CJK stems active in EXTREME
    if level >= LEVEL_EXTREME {
        for &stem in CJK_SUPER_EXTREME_STEMS {
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
    // Suggestive phrases active in ALL levels (LOW hardened to Moderate)
    for &phrase in SUGGESTIVE_ECCHI_PHRASES {
        if lower.contains(phrase) {
            return true;
        }
    }
    // Extreme phrases active in MODERATE & EXTREME
    if level >= LEVEL_MODERATE {
        for &phrase in EXTREME_SENSITIVITY_PHRASES {
            if lower.contains(phrase) {
                return true;
            }
        }
    }
    // Super Extreme phrases active in EXTREME
    if level >= LEVEL_EXTREME {
        for &phrase in SUPER_EXTREME_PHRASES {
            if lower.contains(phrase) {
                return true;
            }
        }
    }

    // 3. Substring checks for stems
    for &stem in HARDCORE_STEMS {
        if lower.contains(stem) {
            return true;
        }
    }
    // Suggestive stems active in ALL levels (LOW hardened to Moderate)
    for &stem in SUGGESTIVE_STEMS {
        if lower.contains(stem) {
            return true;
        }
    }
    // Extreme stems active in MODERATE & EXTREME
    if level >= LEVEL_MODERATE {
        for &stem in EXTREME_STEMS {
            if lower.contains(stem) {
                return true;
            }
        }
    }
    // Super Extreme stems active in EXTREME
    if level >= LEVEL_EXTREME {
        for &stem in SUPER_EXTREME_STEMS {
            if lower.contains(stem) {
                return true;
            }
        }
    }

    // 4. Delimited token matching (words separated by whitespace, dashes, slashes, etc.)
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
        // Suggestive tokens active in ALL levels (LOW hardened to Moderate)
        for &suggestive_token in SUGGESTIVE_ECCHI_TOKENS {
            if clean_token == suggestive_token {
                return true;
            }
        }
        // Extreme tokens active in MODERATE & EXTREME
        if level >= LEVEL_MODERATE {
            for &extreme_token in EXTREME_SENSITIVITY_TOKENS {
                if clean_token == extreme_token {
                    return true;
                }
            }
        }
        // Super Extreme tokens active in EXTREME
        if level >= LEVEL_EXTREME {
            for &super_token in SUPER_EXTREME_TOKENS {
                if clean_token == super_token {
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
        assert!(!is_nsfw_text_with_level("Summer Beach Special OVA", "https://stream.to/v1", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Summer Beach Special OVA", "https://stream.to/v1", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Summer Beach Special OVA", "https://stream.to/v1", None, LEVEL_EXTREME));
        assert!(is_nsfw_text_with_level("Hot Spring & Bikini Vacation", "https://stream.to/v2", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Girl in Swimsuit", "https://stream.to/v3", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Island Summer Edition", "https://stream.to/v4", None, LEVEL_MODERATE));
    }

    #[test]
    fn test_text_classification_cjk_stems() {
        assert!(!is_nsfw_text_with_level("水着の美少女", "https://jp.anime/play", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("水着の美少女", "https://jp.anime/play", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("水着の美少女", "https://jp.anime/play", None, LEVEL_EXTREME));
        assert!(is_nsfw_text("18禁 成年コミック アニメ化", "https://jp.anime/play", None));
        assert!(is_nsfw_text_with_level("ビキニグラビア", "https://jp.anime/play", None, LEVEL_MODERATE));
        assert!(is_nsfw_text("エロマンガ先生", "https://jp.anime/play", None));
        assert!(is_nsfw_text_with_level("裏垢女子の秘密", "https://jp.anime/play", None, LEVEL_EXTREME));
    }

    #[test]
    fn test_text_classification_sensitivity_levels() {
        // Hardcore should trigger on LOW, MODERATE, and EXTREME
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Hentai Haven Episode 1", "https://h.org", None, LEVEL_EXTREME));

        // Ecchi should trigger on ALL levels (LOW hardened to Moderate)
        assert!(is_nsfw_text_with_level("High School DxD Season 1", "https://stream.org", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("High School DxD Season 1", "https://stream.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("High School DxD Season 1", "https://stream.org", None, LEVEL_EXTREME));

        // Beach / Swimsuit is allowed on LOW, and strictly filtered in MODERATE and EXTREME (MODERATE hardened to Extreme)
        assert!(!is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Girl in Swimsuit OVA", "https://stream.org", None, LEVEL_EXTREME));

        // Harem / Fanservice tag should trigger on MODERATE and EXTREME
        assert!(!is_nsfw_text_with_level("Ordinary Show", "https://site.org", Some("Harem"), LEVEL_LOW));
        assert!(is_nsfw_text_with_level("Ordinary Show", "https://site.org", Some("Harem"), LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Ordinary Show", "https://site.org", Some("Harem"), LEVEL_EXTREME));

        // Super Extreme romance/ecchi tropes trigger on EXTREME
        assert!(!is_nsfw_text_with_level("Domestic Girlfriend Special", "https://site.org", None, LEVEL_LOW));
        assert!(!is_nsfw_text_with_level("Domestic Girlfriend Special", "https://site.org", None, LEVEL_MODERATE));
        assert!(is_nsfw_text_with_level("Domestic Girlfriend Special", "https://site.org", None, LEVEL_EXTREME));
    }

    #[test]
    fn test_text_classification_clean_mainstream() {
        assert!(!is_nsfw_text("Attack on Titan Season 4", "https://crunchy.com/aot-s4", Some("Action, Fantasy")));
        assert!(!is_nsfw_text("Breaking Bad", "https://netflix.com/title/123", Some("Drama, Crime")));
        assert!(!is_nsfw_text("Spider-Man: Across the Spider-Verse", "https://sony.com/spiderman", None));
    }
}
