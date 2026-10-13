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
    "decensored",
    // Specific Notorious Hentai Single-Word Franchises & Stems
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
    "bakunyuu",
    "kyonyuu",
    "oppai",
    "nukige",
    "eromanga",
    "erogame",
    "onichichi",
    "seikatsushuukan",
    "smut",
    "botehara",
    "fella",
    "fellatio",
    "cunnilingus",
    "pegging",
    "shemale",
    "transsexual",
    "hentaistream",
    "muchohentai",
    "hentaimama",
    "hentaicity",
    "hentaipulse",
    "underhentai",
    "purehentai",
    "hentai2read",
    "asmhentai",
    "hentaicdn",
    "hentaidude",
    "milf",
    "bbw",
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

/// Multi-word adult / hentai franchise phrases (matched as case-insensitive substrings).
const EXPLICIT_ADULT_PHRASES: &[&str] = &[
    "boku no pico",
    "bible black",
    "oni chichi",
    "onichichi",
    "night shift nurses",
    "la blue girl",
    "seikatsu shuukan",
    "resort boin",
    "otome dori",
    "shoujo ramune",
    "gakuen de jikan",
    "kanojo x kanojo",
    "drop out",
    "pink pineapple",
    "queen bee",
    "green bunny",
    "white bear",
    "natural high",
    "ms pictures",
    "mary jane",
    "tsun tsun maid",
    "tamashii insert",
    "himekishi",
    "joshi luck",
    "urotsukidoji",
    "cream lemon",
    "adventure kid",
    "kyonyuu fantasy",
    "futanari bitch",
    "dokidoki little",
    "marshmallow imouto",
    "meshimase",
    "chikan joushaden",
    "velvet kiss",
    "aki sora",
    "ane wa yanmama",
    "uncensored ova",
    "uncensored anime",
    "h-anime",
    "h anime",
    "r-18 anime",
    "r18 anime",
    "adult anime",
    "adult animation",
    "hentai anime",
    "hentai ova",
    "mankitsu happening",
    "itadaki seieki",
    "discipline zero",
    "starless",
    "front innocent",
    "yarichin bitch",
    "chiisana tsubomi",
    "saimin seishidou",
    "enjo kouhai",
    "maki-chan to nau",
    "imouto biyori",
    "kansen sodom",
    "makai tenshi djibril",
    "baku ane",
    "eroge h-game",
    "shikkoku no shaga",
    "kuro gal",
    "swing out sisters",
    "gakuen saimin",
    "koikishi purely",
    "sweet home: h na",
    "tiny evil",
    "brand new maid",
    "ane chikan",
    "ane kyun",
    "harem time",
    "ero konbini",
    "joshi ochi",
    "25-sai no joshikousei",
    "soryo to majiwaru",
    "skirt no naka wa",
    "araiya-san",
    "yubisaki kara no honki",
    "kuro gal o/s",
    "fella hame",
    "ryoujoku famiresu",
    "houkago no yuutousei",
    "shounen maid kuro-kun",
    "boku no yayoi-san",
    "yariman bitch",
    "oppai heart",
    "zettai junpaku mahou shoujo",
    "mama katsu",
    "papa katsu",
    "dokidoki oyako",
    "onsen de w",
    "maid ane",
    "nuki doki",
    "bitch gakuen",
    "tsundere inran shoujo",
    "bishoujo mangekyou",
    "sayonara o oshiete",
    "kara no shoujo",
    "cartagra",
    "soushi souai",
    "kanojo ga yatsu ni",
    "netorarete",
    "inyouchuu",
    "kuro no kyoushitsu",
    "inma seiden",
    "bible pink",
    "kagirohi",
    "dark shell",
    "viper gts",
    "dragon knight 4",
    "pia carrot",
    "kakyuusei",
    "shoujo sect",
    "boin dori",
    "dekisoko",
    "choukou tenshi",
    "fault!!",
    "kuttsukiboshi",
    "bijukubo",
    "himesama navigation",
    "tsunpuri",
    "fella pure",
    "fella clinic",
    "oppai gakuen",
    "eroge de subete",
    "inmou",
    "kyonyuu reijou",
    "bakunyuu maid",
    "chikan densha",
    "chikan juunin",
    "taboo charm",
    "kyonyuu hime",
    "oppai life",
];

/// Japanese, Chinese, and CJK adult / hentai tokens and stems (matched as case-insensitive substrings).
const CJK_ADULT_PHRASES: &[&str] = &[
    // Rating & Censorship
    "18禁",
    "無修正",
    "无码",
    "無碼",
    "成人向け",
    "裏アニメ",
    "裏ビデオ",
    "裏本",
    "里番",
    // Adult anime & game categories
    "エロアニメ",
    "エロ動画",
    "エロゲ",
    "エロ漫画",
    "エロい",
    "同人誌",
    "同人cg",
    "同人音声",
    // Explicit genres & tropes
    "中出し",
    "中出",
    "調教",
    "调教",
    "痴漢",
    "近親相姦",
    "乱伦",
    "乱交",
    "群交",
    "触手",
    "オナニー",
    "陵辱",
    "寝取られ",
    "催眠",
    "パイズリ",
    "アヘ顔",
    "ボテ腹",
    "拘束",
    "奴隷",
    "生ハメ",
    "潮吹き",
    "ハメ撮り",
    "絶頂",
    "肉便器",
    "熟女",
    "人妻",
    "母乳",
    "乱暴",
    "淫乱",
    "淫魔",
    "発情",
    "雌豚",
    "牝犬",
    "巨乳",
    "爆乳",
    "ヘンタイ",
    "アナル",
    "フェラ",
    "クンニ",
    "バイブ",
    "電マ",
    "スカトロ",
    "放尿",
    "潮吹",
    "処女喪失",
    "初体験",
    "援助交際",
    "援交",
    "パパ活",
    "変態",
    "色情",
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
    "hentaicity",
    "hentaipulse",
    "hentaimama",
    "muchohentai",
    "animehentai",
    "hentai2read",
    "simply-hentai",
    "asmhentai",
    "hentaicdn",
    "hentaistream",
    "hentaidude",
    "underhentai",
    "7mmtv",
    "tokyomotion",
    "xhamster",
    "youporn",
    "redtube",
    "tube8",
    "porn555",
    "xmoviesforyou",
    "beeg.com",
    "chaturbate",
    "myhentaigallery",
    "hitomi.la",
    "e-hentai.org",
    "exhentai.org",
    "hentai-foundry",
    "gelbooru.com",
    "danbooru.donmai.us",
    "tbib.org",
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

    // Direct check for explicit rating symbols
    if lower_title.contains("r-18") || lower_title.contains("r18+") || lower_title.contains("18+") {
        return true;
    }
    if lower_url.contains("r-18") || lower_url.contains("r18+") {
        return true;
    }

    // 2. Check multi-word notorious hentai phrases
    for phrase in EXPLICIT_ADULT_PHRASES {
        if lower_title.contains(phrase) || lower_url.contains(phrase) {
            return true;
        }
    }

    // 3. Check CJK / Japanese / Chinese adult and hentai stems
    for phrase in CJK_ADULT_PHRASES {
        if lower_title.contains(phrase) || lower_url.contains(phrase) {
            return true;
        }
    }

    // 4. Tokenize title and check against explicit adult/hentai single tokens
    let title_tokens = extract_tokens(&lower_title);
    for token in &title_tokens {
        for adult_token in EXPLICIT_ADULT_TOKENS {
            if token == adult_token {
                return true;
            }
        }
    }

    // 5. Tokenize URL slug segments (often contains /hentai/ or /xxx/)
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
            || token == "rance"
            || token == "kuroinu"
            || token == "taimanin"
            || token == "mankitsu"
            || token == "overflow"
            || token == "euphoria"
            || token == "erotica"
            || token == "erotic"
            || token == "eroge"
            || token == "ahegao"
            || token == "paizuri"
            || token == "nakadashi"
            || token == "chikan"
        {
            return true;
        }
    }

    // 6. Check tags, genres, or plot synopsis if provided
    if let Some(tag_str) = tags {
        let lower_tags = tag_str.to_lowercase();
        if lower_tags.contains("18+")
            || lower_tags.contains("r-18")
            || lower_tags.contains("r18+")
            || lower_tags.contains("18禁")
        {
            return true;
        }

        // Check multi-word phrases and CJK adult terms in tags
        for phrase in EXPLICIT_ADULT_PHRASES {
            if lower_tags.contains(phrase) {
                return true;
            }
        }
        for phrase in CJK_ADULT_PHRASES {
            if lower_tags.contains(phrase) {
                return true;
            }
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
            for adult_token in EXPLICIT_ADULT_TOKENS {
                if token == adult_token {
                    return true;
                }
            }
        }
    }

    false
}

/// Ultra-fast Zero-Neural-Network Skin Chrominance Heuristic.
/// Analyzes image pixels in YCbCr space in under 20 microseconds.
/// Returns true if excessive exposed human/anime skin tone (> 48%) is detected.
pub fn is_skin_exposure_excessive(rgb_bytes: &[u8], bytes_per_pixel: usize) -> bool {
    if rgb_bytes.len() < 12 {
        return false;
    }

    let bpp = if bytes_per_pixel >= 3 { bytes_per_pixel } else { 3 };
    let total_pixels = rgb_bytes.len() / bpp;
    if total_pixels == 0 {
        return false;
    }

    // Sample up to 1,024 pixels with uniform stride for sub-microsecond evaluation
    let step = (total_pixels / 1024).max(1);
    let mut sampled_count = 0usize;
    let mut skin_count = 0usize;

    let mut i = 0usize;
    while i < total_pixels {
        let offset = i * bpp;
        if offset + 2 >= rgb_bytes.len() {
            break;
        }

        let r = rgb_bytes[offset] as f32;
        let g = rgb_bytes[offset + 1] as f32;
        let b = rgb_bytes[offset + 2] as f32;

        let y = 0.299 * r + 0.587 * g + 0.114 * b;
        let cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b;
        let cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b;

        // YCbCr skin tone cluster encompassing human & anime flesh tones
        if cb >= 80.0 && cb <= 135.0 && cr >= 133.0 && cr <= 178.0 && y >= 40.0 {
            skin_count += 1;
        }

        sampled_count += 1;
        i += step;
    }

    if sampled_count == 0 {
        return false;
    }

    let skin_ratio = (skin_count as f32) / (sampled_count as f32);
    // > 48% exposed skin indicates significant nudity/swimsuit coverage
    skin_ratio >= 0.48
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
        // Newly added untagged hentai franchise stems
        assert!(is_nsfw_text("Boku no Pico", "https://anime.tv/watch/boku-no-pico", None));
        assert!(is_nsfw_text("Bible Black: La Noche de Walpurgis", "https://anime.tv/watch/bible-black", None));
        assert!(is_nsfw_text("Kuroinu: Kedakaki Seishi ni Somaru Yami", "https://anime.tv/watch/kuroinu", None));
        assert!(is_nsfw_text("Taimanin Asagi Episode 1", "https://anime.tv/watch/taimanin-asagi", None));
        assert!(is_nsfw_text("Oni Chichi: Re-born", "https://anime.tv/watch/oni-chichi", None));
        assert!(is_nsfw_text("Seikatsu Shuukan OVA", "https://anime.tv/watch/seikatsu-shuukan", None));
        assert!(is_nsfw_text("Euphoria HD", "https://anime.tv/watch/euphoria", None));
        assert!(is_nsfw_text("Resort Boin", "https://anime.tv/watch/resort-boin", None));
        assert!(is_nsfw_text("Shoujo Ramune", "https://anime.tv/watch/shoujo-ramune", None));
        assert!(is_nsfw_text("Otome Dori", "https://anime.tv/watch/otome-dori", None));
        assert!(is_nsfw_text("Night Shift Nurses", "https://anime.tv/watch/night-shift-nurses", None));
        assert!(is_nsfw_text("Gakuen de Jikan yo Tomare", "https://anime.tv/watch/jikan-tomare", None));
        assert!(is_nsfw_text("Pink Pineapple Selection", "https://anime.tv/watch/pink-pineapple", None));
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
        assert!(!is_nsfw_text("My Hero Academia", "https://anime.tv/watch/boku-no-hero-academia", Some("Shounen, Superhero")));
        assert!(!is_nsfw_text("Black Clover", "https://anime.tv/watch/black-clover", Some("Action, Fantasy")));
        assert!(!is_nsfw_text("Kuroko no Basket", "https://anime.tv/watch/kuroko-no-basket", Some("Sports, Comedy")));
        assert!(!is_nsfw_text("JoJo's Bizarre Adventure", "https://anime.tv/watch/jojo", Some("Action, Supernatural")));
        assert!(!is_nsfw_text("Demon Slayer: Kimetsu no Yaiba", "https://anime.tv/watch/demon-slayer", Some("Action, Fantasy")));
    }

    #[test]
    fn test_detects_general_adult_and_jav() {
        assert!(is_nsfw_text("JAV Uncensored Collection", "https://stream.com/watch/jav-123", None));
        assert!(is_nsfw_text("Hot Erotica Night", "https://stream.com/movie/hot-erotica", None));
        assert!(is_nsfw_text("Random Title", "https://spankbang.com/watch/video", None));
        assert!(is_nsfw_text("Title with adult rating", "https://stream.com/watch/title", Some("Drama, 18+, Adult")));
    }

    #[test]
    fn test_skin_exposure_detection() {
        // High skin exposure sample (e.g. RGB peach/skin tones [255, 205, 180])
        let mut nsfw_image = Vec::new();
        for _ in 0..100 {
            nsfw_image.extend_from_slice(&[255, 205, 180]);
        }
        assert!(is_skin_exposure_excessive(&nsfw_image, 3));

        // Clean image (e.g. Blue sky/ocean tones [50, 100, 220])
        let mut clean_image = Vec::new();
        for _ in 0..100 {
            clean_image.extend_from_slice(&[50, 100, 220]);
        }
        assert!(!is_skin_exposure_excessive(&clean_image, 3));
    }

    #[test]
    fn test_detects_cjk_and_disguised_hentai() {
        assert!(is_nsfw_text("18禁裏アニメ OVA 01", "https://anime.tv/watch/video-1", None));
        assert!(is_nsfw_text("【無修正】Ane wa Yanmama", "https://stream.com/watch/video-2", None));
        assert!(is_nsfw_text("中出し調教 Special", "https://stream.com/watch/video-3", None));
        assert!(is_nsfw_text("新作里番合集", "https://stream.com/watch/video-4", None));
        assert!(is_nsfw_text("Netorare 寝取られ OVA", "https://stream.com/watch/video-5", None));
        assert!(is_nsfw_text("無修正 JAV 001", "https://stream.com/watch/video-6", None));
        assert!(is_nsfw_text("Eroge エロアニメ 01", "https://stream.com/watch/video-7", None));
        assert!(is_nsfw_text("Untagged Anime", "https://stream.com/watch/video-8", Some("18禁, 裏アニメ")));
    }
}
