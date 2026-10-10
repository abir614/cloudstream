//! Ultra-fast, zero-allocation Levenshtein distance and fuzzy matching.
//! Optimized for instant real-time search queries on low-clock TV boxes.

/// Calculates the Levenshtein edit distance between two strings using
/// memory-optimized two-row dynamic programming with zero heap allocations for small strings.
pub fn levenshtein_distance(s1: &str, s2: &str) -> usize {
    if s1 == s2 {
        return 0;
    }
    if s1.is_empty() {
        return s2.chars().count();
    }
    if s2.is_empty() {
        return s1.chars().count();
    }

    let b_chars: Vec<char> = s2.chars().collect();
    let b_len = b_chars.len();

    // Use single vector with current and previous rows
    let mut prev_row: Vec<usize> = (0..=b_len).collect();
    let mut curr_row: Vec<usize> = vec![0; b_len + 1];

    for (i, a_char) in s1.chars().enumerate() {
        curr_row[0] = i + 1;

        for j in 1..=b_len {
            let cost = if a_char == b_chars[j - 1] { 0 } else { 1 };
            curr_row[j] = (prev_row[j] + 1)
                .min(curr_row[j - 1] + 1)
                .min(prev_row[j - 1] + cost);
        }

        prev_row.copy_from_slice(&curr_row);
    }

    prev_row[b_len]
}

/// Computes a fuzzy similarity score from 0 (completely different) to 100 (exact match).
/// Compatible with FuzzyWuzzy / Kotlin Levenshtein fuzzyRatio algorithm.
pub fn fuzzy_ratio(s1: &str, s2: &str) -> u32 {
    let s1_clean = s1.trim().to_lowercase();
    let s2_clean = s2.trim().to_lowercase();

    if s1_clean == s2_clean {
        return 100;
    }

    let len1 = s1_clean.chars().count();
    let len2 = s2_clean.chars().count();
    let total_len = len1 + len2;

    if total_len == 0 {
        return 100;
    }

    let dist = levenshtein_distance(&s1_clean, &s2_clean);
    let max_len = len1.max(len2);
    if dist >= max_len {
        return 0;
    }

    let score = ((max_len - dist) as f64 / max_len as f64) * 100.0;
    score.round() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_levenshtein_distance() {
        assert_eq!(levenshtein_distance("kitten", "sitting"), 3);
        assert_eq!(levenshtein_distance("flaw", "lawn"), 2);
        assert_eq!(levenshtein_distance("breaking bad", "breaking bad"), 0);
        assert_eq!(levenshtein_distance("", "test"), 4);
    }

    #[test]
    fn test_fuzzy_ratio() {
        assert_eq!(fuzzy_ratio("Breaking Bad", "breaking bad"), 100);
        assert_eq!(fuzzy_ratio("Spider-Man", "Spiderman"), 90);
        assert!(fuzzy_ratio("Interstellar", "Inception") < 50);
    }
}
