use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;

use crate::iptv::m3u::M3uParser;
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

/// JNI bridge to parse M3U IPTV playlist content into JSON with high speed and zero JVM overhead.
#[no_mangle]
pub extern "system" fn Java_com_lagradost_cloudstream3_services_NativeCoreBridge_parseM3uChannels(
    mut env: JNIEnv,
    _class: JClass,
    content_input: JString,
) -> jstring {
    let content_str: String = match env.get_string(&content_input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let channels = M3uParser::parse(&content_str);
    let json_output = serde_json::to_string(&channels).unwrap_or_else(|_| "[]".to_string());

    match env.new_string(json_output) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
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
