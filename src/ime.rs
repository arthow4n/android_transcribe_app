use jni::objects::{JClass, JObject, JString};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::{Arc, Mutex};

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *IME_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *IME_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
    streaming: jni::sys::jboolean,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        // The IME keyboard is manual tap-to-stop; no silence auto-stop.
        voice_session::start_recording_mode(env, state, false, streaming != 0);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_setLanguageNative(
    mut env: JNIEnv,
    _class: JClass,
    language: JString,
) {
    match env.get_string(&language) {
        Ok(value) => {
            crate::engine::set_language(Some(value.to_string_lossy().into_owned()));
        }
        Err(error) => log::error!("failed to read IME language: {error}"),
    }
}

/// Reload the shared engine after the keyboard selects a different installed
/// model. The model picker runs in the app process, but the IME has its own
/// native state and needs an explicit reload for the change to take effect.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_reloadModelNative(
    env: JNIEnv,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let service_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };
    std::thread::spawn(move || {
        crate::engine::reset();
        let _ = crate::engine::ensure_loaded_from_thread(&vm, &service_ref);
    });
}
