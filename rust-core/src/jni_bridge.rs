use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

use crate::security::ssrf::validate_url_safety;

/// JNI bridge to validate URL safety against SSRF and private network access.
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_validateUrlSafety(
    mut env: JNIEnv,
    _class: JClass,
    url_input: JString,
) -> jboolean {
    let url_str: String = match env.get_string(&url_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match validate_url_safety(&url_str) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

/// Get native core version and architecture
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_getCoreInfo(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let arch = if cfg!(target_arch = "aarch64") {
        "ARMv8 (aarch64)"
    } else if cfg!(target_arch = "arm") {
        "ARMv7 (armeabi-v7a)"
    } else if cfg!(target_arch = "x86_64") {
        "x86_64"
    } else {
        "Unknown"
    };

    let info = format!("CloudStream-Core-Rust v0.1.0 [{}]", arch);
    match env.new_string(info) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to generate a cryptographically secure 256-bit ephemeral session nonce
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_generateSessionNonce(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let nonce = match crate::security::apk_verifier::generate_session_nonce() {
        Ok(n) => n,
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(nonce) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to perform Z+ Zero-Trust End-to-End identity verification on an APK file
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_verifyApkIdentity(
    mut env: JNIEnv,
    _class: JClass,
    apk_path_input: JString,
    session_nonce_input: JString,
    expected_pkg_input: JString,
    actual_pkg_input: JString,
    expected_cert_input: JString,
    actual_cert_input: JString,
    installed_version_code: jni::sys::jlong,
    apk_version_code: jni::sys::jlong,
    repo_header_hex_input: JString,
) -> jstring {
    let apk_path: String = match env.get_string(&apk_path_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let session_nonce: String = match env.get_string(&session_nonce_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let expected_pkg: String = match env.get_string(&expected_pkg_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let actual_pkg: String = match env.get_string(&actual_pkg_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let expected_cert: String = match env.get_string(&expected_cert_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let actual_cert: String = match env.get_string(&actual_cert_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let repo_header_hex: Option<String> = if !repo_header_hex_input.is_null() {
        match env.get_string(&repo_header_hex_input) {
            Ok(s) => {
                let str_val: String = s.into();
                if str_val.trim().is_empty() { None } else { Some(str_val) }
            }
            Err(_) => None,
        }
    } else {
        None
    };

    let result = crate::security::apk_verifier::verify_apk_identity(
        &apk_path,
        &session_nonce,
        &expected_pkg,
        &actual_pkg,
        &expected_cert,
        &actual_cert,
        installed_version_code,
        apk_version_code,
        repo_header_hex.as_deref(),
    );

    let json_output = serde_json::to_string(&result)
        .unwrap_or_else(|_| "{\"is_valid\":false,\"error_message\":\"Serialization error\"}".to_string());
    match env.new_string(json_output) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to verify an attestation token
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_verifyAttestationToken(
    mut env: JNIEnv,
    _class: JClass,
    session_nonce_input: JString,
    token_input: JString,
    file_hash_input: JString,
    pkg_name_input: JString,
    cert_fingerprint_input: JString,
    version_code: jni::sys::jlong,
) -> jboolean {
    let session_nonce: String = match env.get_string(&session_nonce_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let token: String = match env.get_string(&token_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let file_hash: String = match env.get_string(&file_hash_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let pkg_name: String = match env.get_string(&pkg_name_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let cert_fingerprint: String = match env.get_string(&cert_fingerprint_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if crate::security::apk_verifier::verify_attestation_token(
        &session_nonce,
        &token,
        &file_hash,
        &pkg_name,
        &cert_fingerprint,
        version_code,
    ) {
        1
    } else {
        0
    }
}

/// JNI bridge to retrieve a cached value from the native LRU cache
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeCacheGet(
    mut env: JNIEnv,
    _class: JClass,
    cache_name_input: JString,
    key_input: JString,
) -> jstring {
    let cache_name: String = match env.get_string(&cache_name_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let key: String = match env.get_string(&key_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    if let Some(val) = crate::cache::cache_get(&cache_name, &key) {
        match env.new_string(val) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

/// JNI bridge to put a key-value pair into the native LRU cache
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeCachePut(
    mut env: JNIEnv,
    _class: JClass,
    cache_name_input: JString,
    key_input: JString,
    value_input: JString,
) {
    let cache_name: String = match env.get_string(&cache_name_input) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let key: String = match env.get_string(&key_input) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let value: String = match env.get_string(&value_input) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    crate::cache::cache_put(&cache_name, key, value);
}

/// JNI bridge to remove a key from the native LRU cache
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeCacheRemove(
    mut env: JNIEnv,
    _class: JClass,
    cache_name_input: JString,
    key_input: JString,
) -> jboolean {
    let cache_name: String = match env.get_string(&cache_name_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let key: String = match env.get_string(&key_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if crate::cache::cache_remove(&cache_name, &key) {
        1
    } else {
        0
    }
}

/// JNI bridge to clear a native LRU cache
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeCacheClear(
    mut env: JNIEnv,
    _class: JClass,
    cache_name_input: JString,
) {
    let cache_name: String = match env.get_string(&cache_name_input) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    crate::cache::cache_clear(&cache_name);
}

/// JNI bridge to parse SubRip (.srt) subtitles with zero JVM GC pressure
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeParseSubtitles(
    mut env: JNIEnv,
    _class: JClass,
    content_input: JString,
) -> jstring {
    let content: String = match env.get_string(&content_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let cues = crate::subtitles::parse_srt(&content);
    let json = match serde_json::to_string(&cues) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to sanitize and strip tracking parameters from a URL
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeSanitizeUrl(
    mut env: JNIEnv,
    _class: JClass,
    url_input: JString,
) -> jstring {
    let url: String = match env.get_string(&url_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let cleaned = crate::network::sanitize_url(&url);
    match env.new_string(cleaned) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to resolve a relative URL against a base URL
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeResolveUrl(
    mut env: JNIEnv,
    _class: JClass,
    base_input: JString,
    relative_input: JString,
) -> jstring {
    let base: String = match env.get_string(&base_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let relative: String = match env.get_string(&relative_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let resolved = crate::network::resolve_url(&base, &relative);
    match env.new_string(resolved) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to compute 64-bit fast FNV-1a hash
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeFastHash64(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jni::sys::jlong {
    let text: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    crate::crypto::fast_hash64_str(&text) as jni::sys::jlong
}

/// JNI bridge to compute SHA-256 hash in native Rust
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeSha256(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let text: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let hash = crate::crypto::sha256_hex(text.as_bytes());
    match env.new_string(hash) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to compute MD5 hash in native Rust
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeMd5(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let text: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let hash = crate::crypto::md5_hex(text.as_bytes());
    match env.new_string(hash) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to compute SHA-256 hash of a file directly on disk in native Rust
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeFileSha256(
    mut env: JNIEnv,
    _class: JClass,
    file_path_input: JString,
) -> jstring {
    let file_path: String = match env.get_string(&file_path_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match crate::security::apk_verifier::compute_file_sha256(&file_path) {
        Ok(hash) => match env.new_string(hash) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}


