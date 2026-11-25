use miniz_oxide::deflate::{compress_to_vec, compress_to_vec_zlib};
use miniz_oxide::inflate::{decompress_to_vec, decompress_to_vec_zlib};
use thiserror::Error;

/// Maximum allowed decompressed output size: 50 MB.
/// Prevents decompression bomb (zip bomb) attacks where a small input inflates to gigabytes.
const MAX_DECOMPRESSED_BYTES: usize = 50 * 1024 * 1024;

#[derive(Error, Debug)]
pub enum CompressionError {
    #[error("Decompression failed: {0}")]
    Decompress(String),
    #[error("Decompressed bytes are not valid UTF-8: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),
    #[error("Decompression bomb rejected: output would exceed {MAX_DECOMPRESSED_BYTES} bytes")]
    SizeLimit,
}

/// Compresses a raw byte buffer using raw DEFLATE (level 6).
pub fn compress_deflate(input: &[u8]) -> Vec<u8> {
    compress_to_vec(input, 6)
}

/// Decompresses raw DEFLATE bytes into an uncompressed buffer.
/// Returns [CompressionError::SizeLimit] if output would exceed [MAX_DECOMPRESSED_BYTES].
pub fn decompress_deflate(input: &[u8]) -> Result<Vec<u8>, CompressionError> {
    // Pre-flight: reject inputs with a suspiciously high theoretical expansion ratio.
    // Raw DEFLATE max expansion is ~1032x; practical safe compressed sizes are ≤48 KB for 50 MB output.
    if input.len() > MAX_DECOMPRESSED_BYTES {
        return Err(CompressionError::SizeLimit);
    }
    let output = decompress_to_vec(input).map_err(|e| CompressionError::Decompress(format!("{e:?}")))?;
    if output.len() > MAX_DECOMPRESSED_BYTES {
        return Err(CompressionError::SizeLimit);
    }
    Ok(output)
}

/// Compresses raw bytes using zlib-wrapped DEFLATE.
pub fn compress_zlib(input: &[u8]) -> Vec<u8> {
    compress_to_vec_zlib(input, 6)
}

/// Decompresses zlib-wrapped DEFLATE bytes.
/// Returns [CompressionError::SizeLimit] if output would exceed [MAX_DECOMPRESSED_BYTES].
pub fn decompress_zlib(input: &[u8]) -> Result<Vec<u8>, CompressionError> {
    if input.len() > MAX_DECOMPRESSED_BYTES {
        return Err(CompressionError::SizeLimit);
    }
    let output = decompress_to_vec_zlib(input).map_err(|e| CompressionError::Decompress(format!("{e:?}")))?;
    if output.len() > MAX_DECOMPRESSED_BYTES {
        return Err(CompressionError::SizeLimit);
    }
    Ok(output)
}

/// Compresses a UTF-8 string into raw DEFLATE bytes.
pub fn compress_string(input: &str) -> Vec<u8> {
    compress_deflate(input.as_bytes())
}

/// Decompresses raw DEFLATE bytes back into a UTF-8 string.
pub fn decompress_string(input: &[u8]) -> Result<String, CompressionError> {
    let bytes = decompress_deflate(input)?;
    String::from_utf8(bytes).map_err(CompressionError::Utf8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_deflate_roundtrip_simple() {
        let original = "Hello CloudStream with high-speed DEFLATE compression!";
        let compressed = compress_string(original);
        assert!(!compressed.is_empty());
        let decompressed = decompress_string(&compressed).expect("decompression must succeed");
        assert_eq!(decompressed, original);
    }

    #[test]
    fn test_deflate_compression_ratio_on_repetitive_data() {
        let repetitive = "#EXTM3U\n#EXTINF:-1 tvg-id=\"1\" group-title=\"Live\",Channel 1\nhttp://example.com/live/1.m3u8\n".repeat(100);
        let compressed = compress_string(&repetitive);
        assert!(
            compressed.len() < repetitive.len() / 4,
            "Repetitive data should compress by at least 75%"
        );
        let restored = decompress_string(&compressed).expect("restoration must succeed");
        assert_eq!(restored, repetitive);
    }

    #[test]
    fn test_zlib_roundtrip() {
        let original = b"Binary stream payload for hardware player buffer cache";
        let compressed = compress_zlib(original);
        let decompressed = decompress_zlib(&compressed).expect("zlib decompression must succeed");
        assert_eq!(&decompressed[..], &original[..]);
    }
}
