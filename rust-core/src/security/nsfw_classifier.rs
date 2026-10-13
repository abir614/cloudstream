//! Core Image & Poster Mathematical Classification Interface.
//!
//! Per user architecture, textual keyword and dictionary heuristics have been removed
//! in favor of the high-performance mathematical poster detection engine in `poster_detector.rs`.

pub use crate::security::poster_detector::is_skin_exposure_excessive;

/// Text classification stub: textual/word-list heuristics removed to count solely on mathematical poster detection.
pub fn is_nsfw_text(_title: &str, _url: &str, _tags: Option<&str>) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

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
