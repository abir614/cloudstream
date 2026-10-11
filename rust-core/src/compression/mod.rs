pub mod deflate;

pub use deflate::{
    compress_deflate, compress_string, compress_zlib, decompress_deflate, decompress_string,
    decompress_zlib, CompressionError,
};
