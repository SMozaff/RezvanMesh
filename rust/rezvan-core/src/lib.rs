use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;

mod engine;
mod routing;
mod power;
mod session;
mod crypto;
mod action;

use engine::MeshEngine;

fn jbytearray_to_vec(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    let size = env.get_array_length(array).map_err(|e| e.to_string())? as usize;
    let mut buf = vec![0u8; size];
    // JNI get_byte_array_region expects &mut [i8]; transmute the Vec<u8> buffer
    let buf_slice = unsafe {
        std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, size)
    };
    env.get_byte_array_region(array, 0, buf_slice)
        .map_err(|e| e.to_string())?;
    Ok(buf)
}

fn jbytearray_to_array<const N: usize>(env: &mut JNIEnv, array: &JByteArray) -> Result<[u8; N], String> {
    let bytes = jbytearray_to_vec(env, array)?;
    if bytes.len() != N {
        return Err(format!("expected {} bytes, got {}", N, bytes.len()));
    }
    let mut arr = [0u8; N];
    arr.copy_from_slice(&bytes);
    Ok(arr)
}

fn vec_to_jbytearray(env: &mut JNIEnv, data: &[u8]) -> Result<jbyteArray, String> {
    let arr = env.byte_array_from_slice(data).map_err(|e| e.to_string())?;
    Ok(arr.into_raw())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    seed: JByteArray,
    _storage_path: JString,
) -> jlong {
    let seed_array = match jbytearray_to_array::<32>(&mut env, &seed) {
        Ok(s) => s,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return 0;
        }
    };

    // Real identity/signing provider (seed-derived Ed25519/X25519).
    // Message encryption is handled separately by vodozemac inside SessionManager.
    let crypto = Box::new(rezvan_crypto::SodiumCryptoProvider);
    let engine = MeshEngine::new(&seed_array, crypto);
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeProcessIncoming(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    packet: JByteArray,
    rssi: jint,
    timestamp_us: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let bytes = match jbytearray_to_vec(&mut env, &packet) {
        Ok(b) => b,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let (decrypted_message, actions) = engine.process_incoming(&bytes, rssi, timestamp_us as u64);

    let mut all_actions = actions;
    if let Some(msg) = decrypted_message {
        all_actions.push(action::Action::NotifyUi {
            decrypted_message: msg,
        });
    }

    if all_actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&all_actions);
    vec_to_jbytearray(&mut env, &serialized).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeTick(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let actions = engine.tick();

    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&mut env, &serialized).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSendMessage(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    recipient_id: JByteArray,
    plaintext: JByteArray,
    message_type: jint,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };

    let recipient = match jbytearray_to_array::<8>(&mut env, &recipient_id) {
        Ok(r) => r,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let plain = match jbytearray_to_vec(&mut env, &plaintext) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = engine.send_message(&recipient, &plain, message_type as u8);
    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&mut env, &serialized).unwrap_or(std::ptr::null_mut())
}

/// Emergency broadcast (packet_type 0x03, signed, sent to every connected
/// peer). Previously there was no JNI export for this at all -- Kotlin's
/// sendBroadcast() was calling nativeSendMessage() with an all-zero
/// recipient instead, which routes through MeshEngine::send_message (the
/// 1:1 Olm-encrypted path). Encrypting to the null NodeId has no
/// established session, so that call silently failed and returned no
/// actions -- emergency broadcasts never actually transmitted anything.
/// Gate 1 direct send. The message ID is created and durably stored by
/// Android before this call; the engine selects a legacy payload fallback when
/// the recipient has not advertised acknowledgement capability.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSendMessageV1(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    recipient_id: JByteArray,
    message_id: JByteArray,
    created_at_ms: jlong,
    message_kind: jint,
    body: JByteArray,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let recipient = match jbytearray_to_array::<8>(&mut env, &recipient_id) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let message_id = match jbytearray_to_array::<16>(&mut env, &message_id) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let body = match jbytearray_to_vec(&mut env, &body) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let actions = engine.send_message_v1(
        &recipient,
        message_id,
        created_at_ms.max(0) as u64,
        message_kind as u8,
        &body,
    );
    if actions.is_empty() {
        return std::ptr::null_mut();
    }
    vec_to_jbytearray(&mut env, &action::serialize_actions(&actions))
        .unwrap_or(std::ptr::null_mut())
}

/// Creates a signed encrypted Gate 1 receipt acknowledgement. Android MUST
/// call this only after the matching inbound message row was committed.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeBuildMessageReceivedAck(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    original_sender: JByteArray,
    message_id: JByteArray,
    created_at_ms: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let original_sender = match jbytearray_to_array::<8>(&mut env, &original_sender) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let message_id = match jbytearray_to_array::<16>(&mut env, &message_id) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let actions = engine.build_received_ack(&original_sender, message_id, created_at_ms.max(0) as u64);
    if actions.is_empty() {
        return std::ptr::null_mut();
    }
    vec_to_jbytearray(&mut env, &action::serialize_actions(&actions))
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSendBroadcast(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    message: JByteArray,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };

    let plain = match jbytearray_to_vec(&mut env, &message) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = engine.send_broadcast(&plain);
    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&mut env, &serialized).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetKeyBundle(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let bundle = engine.key_bundle();
    vec_to_jbytearray(&mut env, &bundle).unwrap_or(std::ptr::null_mut())
}

/// Returns this engine's canonical 8-byte Node ID: SHA-256(Ed25519 public
/// key)[0:8], computed once via `rezvan_common::compute_node_id`. Kotlin
/// must call this instead of independently recomputing a Node ID from the
/// seed (see security audit finding #8 -- IdentityBackupHelper previously
/// hashed the *seed* directly, producing a different ID than the one the
/// engine actually uses on the wire as `originator`).
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetNodeId(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let node_id = engine.node_id();
    vec_to_jbytearray(&mut env, &node_id).unwrap_or(std::ptr::null_mut())
}

/// Diagnostics-only: dump the routing table so the app can show it in the
/// Diagnostics screen. See MeshEngine::routing_snapshot for wire format.
/// Previously there was no way to inspect routing state outside unit tests.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetRoutingSnapshot(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let snapshot = engine.routing_snapshot();
    vec_to_jbytearray(&mut env, &snapshot).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeRegisterPeerKeys(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    peer_id: JByteArray,
    bundle: JByteArray,
) -> jboolean {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let peer = match jbytearray_to_array::<8>(&mut env, &peer_id) {
        Ok(p) => p,
        Err(_) => return 0,
    };
    let b = match jbytearray_to_vec(&mut env, &bundle) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    engine.register_peer_keys(&peer, &b) as jboolean
}

/// Creates a new random shared key for a channel (called when the local
/// user creates a channel) and returns it so the UI can display/export it
/// for other members to join with (QR code, manual entry -- distribution
/// mechanism is intentionally out of scope here, same as sender_key.rs).
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeCreateChannelKey(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    channel_id: jint,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let key = engine.create_channel_key(channel_id as u32);
    vec_to_jbytearray(&mut env, &key.to_vec()).unwrap_or(std::ptr::null_mut())
}

/// Stores a channel key received out-of-band (joining an existing channel
/// with a key shared by another member).
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSetChannelKey(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    channel_id: jint,
    key: JByteArray,
) -> jboolean {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let k = match jbytearray_to_array::<32>(&mut env, &key) {
        Ok(k) => k,
        Err(_) => return 0,
    };
    engine.set_channel_key(channel_id as u32, k);
    1
}

/// Encrypts+signs `message` for the given channel and returns a serialized
/// action envelope (same format as nativeTick/nativeSendMessage) for
/// ActionDispatcher to route -- broadcasts to all connected peers, since
/// there's no channel-membership-aware routing (only actual members have
/// the shared key to decrypt it). Returns null if we don't have a key for
/// this channel yet.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSendChannelMessage(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    channel_id: jint,
    message: JByteArray,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };

    let plain = match jbytearray_to_vec(&mut env, &message) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = engine.send_channel_message(channel_id as u32, &plain);
    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&mut env, &serialized).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetPowerState(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jint {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.get_power_state() as jint
}

fn power_state_from_i32(v: i32) -> Option<crate::power::PowerState> {
    use crate::power::PowerState::*;
    match v {
        0 => Some(Emergency),
        1 => Some(Active),
        2 => Some(Balanced),
        3 => Some(PowerSaver),
        4 => Some(Minimal),
        5 => Some(Hibernation),
        6 => Some(Dead),
        _ => None,
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSetPowerOverride(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    state: jint,
) {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.set_user_override(power_state_from_i32(state));
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeClearPowerOverride(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.set_user_override(None);
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeUpdateBattery(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    level_percent: jint,
    is_charging: jboolean,
) {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.update_battery(level_percent as u8, is_charging != 0);
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    if core_ptr == 0 {
        return;
    }
    unsafe {
        let _ = Box::from_raw(core_ptr as *mut MeshEngine);
    }
        }