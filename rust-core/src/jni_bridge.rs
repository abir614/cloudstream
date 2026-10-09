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

