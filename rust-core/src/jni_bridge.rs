use jni::objects::{JByteArray, JClass, JString};
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
    if url_input.is_null() {
        return 0;
    }
    let url_str: String = match env.get_string(&url_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let safe_res = std::panic::catch_unwind(|| {
        validate_url_safety(&url_str)
    });

    match safe_res {
        Ok(Ok(_)) => 1,
        _ => 0,
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

/// JNI bridge to run a capability-gated WebAssembly extension in native Rust.
/// Returns a JSON string with format:
/// {"success": bool, "logs": [String], "error": String?}
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeRunWasmExtension(
    env: JNIEnv,
    _class: JClass,
    wasm_bytes_input: JByteArray,
    initial_fuel: jni::sys::jlong,
    max_memory_bytes: jni::sys::jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        #[cfg(feature = "wasm-sandbox")]
        {
            let bytes: Vec<u8> = match env.convert_byte_array(&wasm_bytes_input) {
                Ok(b) => b,
                Err(e) => {
                    let res = serde_json::json!({
                        "success": false,
                        "logs": Vec::<String>::new(),
                        "error": format!("JNI convert_byte_array failed: {}", e)
                    });
                    return match env.new_string(res.to_string()) {
                        Ok(js) => js.into_raw(),
                        Err(_) => std::ptr::null_mut(),
                    };
                }
            };

            let sandbox = match crate::sandbox::wasm::WasmSandbox::new() {
                Ok(s) => s,
                Err(e) => {
                    let res = serde_json::json!({
                        "success": false,
                        "logs": Vec::<String>::new(),
                        "error": format!("WasmSandbox initialization failed: {}", e)
                    });
                    return match env.new_string(res.to_string()) {
                        Ok(js) => js.into_raw(),
                        Err(_) => std::ptr::null_mut(),
                    };
                }
            };

            let fuel = if initial_fuel <= 0 { 500_000 } else { initial_fuel as u64 };
            let max_mem = if max_memory_bytes <= 0 { 16 * 1024 * 1024 } else { max_memory_bytes as usize };

            let res = match sandbox.run_extension(&bytes, fuel, max_mem) {
                Ok(logs) => serde_json::json!({
                    "success": true,
                    "logs": logs,
                    "error": null
                }),
                Err(e) => serde_json::json!({
                    "success": false,
                    "logs": Vec::<String>::new(),
                    "error": e.to_string()
                }),
            };

            match env.new_string(res.to_string()) {
                Ok(js) => js.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }

        #[cfg(not(feature = "wasm-sandbox"))]
        {
            let _ = (&env, &wasm_bytes_input, initial_fuel, max_memory_bytes);
            let res = serde_json::json!({
                "success": false,
                "logs": Vec::<String>::new(),
                "error": "WASM sandbox runtime is disabled in this lean build"
            });
            match env.new_string(res.to_string()) {
                Ok(js) => js.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }));

    match result {
        Ok(ptr) => ptr,
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI bridge to verify an offline Ed25519 signature on an update manifest or artifact
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeVerifyEd25519(
    mut env: JNIEnv,
    _class: JClass,
    manifest_input: JString,
    signature_hex_input: JString,
    public_key_hex_input: JString,
) -> jboolean {
    if manifest_input.is_null() || signature_hex_input.is_null() || public_key_hex_input.is_null() {
        return 0;
    }
    let manifest: String = match env.get_string(&manifest_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let signature_hex: String = match env.get_string(&signature_hex_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let public_key_hex: String = match env.get_string(&public_key_hex_input) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let safe_res = std::panic::catch_unwind(|| {
        crate::crypto::ed25519::verify_manifest_signature(&manifest, &signature_hex, &public_key_hex)
    });

    match safe_res {
        Ok(true) => 1,
        _ => 0,
    }
}

/// JNI bridge to encrypt backup bytes with user passphrase using Argon2id + ChaCha20-Poly1305
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeEncryptBackup(
    mut env: JNIEnv,
    _class: JClass,
    data_input: jni::objects::JByteArray,
    passphrase_input: JString,
) -> jni::sys::jbyteArray {
    if data_input.is_null() || passphrase_input.is_null() {
        return std::ptr::null_mut();
    }
    let data = match env.convert_byte_array(&data_input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let passphrase: String = match env.get_string(&passphrase_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let safe_res = std::panic::catch_unwind(|| {
        crate::crypto::backup_cipher::encrypt_backup_payload(&data, &passphrase)
    });

    match safe_res {
        Ok(Ok(encrypted)) => match env.byte_array_from_slice(&encrypted) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// JNI bridge to decrypt backup bytes with user passphrase
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeDecryptBackup(
    mut env: JNIEnv,
    _class: JClass,
    encrypted_input: jni::objects::JByteArray,
    passphrase_input: JString,
) -> jni::sys::jbyteArray {
    if encrypted_input.is_null() || passphrase_input.is_null() {
        return std::ptr::null_mut();
    }
    let encrypted = match env.convert_byte_array(&encrypted_input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let passphrase: String = match env.get_string(&passphrase_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let safe_res = std::panic::catch_unwind(|| {
        crate::crypto::backup_cipher::decrypt_backup_payload(&encrypted, &passphrase)
    });

    match safe_res {
        Ok(Ok(plaintext)) => match env.byte_array_from_slice(&plaintext) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// JNI bridge to check if backup bytes are encrypted with CSENC1 header
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_nativeIsEncryptedBackup(
    env: JNIEnv,
    _class: JClass,
    data_input: jni::objects::JByteArray,
) -> jboolean {
    if data_input.is_null() {
        return 0;
    }
    let data = match env.convert_byte_array(&data_input) {
        Ok(b) => b,
        Err(_) => return 0,
    };

    if crate::crypto::backup_cipher::is_encrypted_backup(&data) { 1 } else { 0 }
}
