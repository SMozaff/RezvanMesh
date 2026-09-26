use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock};

mod action;
mod engine;
mod persistence;
mod power;
mod routing;
mod session;

use engine::MeshEngine;

/// Live engine instances, keyed by the opaque handle handed to Kotlin.
///
/// The JNI layer is called from several Kotlin coroutines (the service's
/// `serviceScope`, the periodic tick job, the Bluetooth callback threads) that
/// can all be inside a native call at the same time. Two problems follow from
/// that, and this registry fixes both:
///
/// 1. **Data race** — every JNI entry point needs `&mut MeshEngine`, which Rust
///    only hands out once. Each engine lives behind its own `Mutex`, and every
///    entry point locks it for the duration of the call.
/// 2. **Use-after-free on destroy** — `nativeDestroy` must be able to tear the
///    engine down while another thread is still inside a call. Holding the
///    engine in an `Arc` means a thread that already resolved the handle keeps
///    the engine alive; removal from the registry only drops *our* reference.
///    A resolved handle is always valid for the duration of the call, and once
///    the handle is removed, subsequent lookups return `None` instead of
///    dereferencing a freed pointer.
///
/// Holding a `jlong` and passing it back is therefore safe in both directions;
/// what must never happen is using a handle that was never issued, or one that
/// has already been destroyed. Both are handled here by `EngineRegistry::get`.
struct EngineRegistry {
    engines: RwLock<HashMap<jlong, Arc<Mutex<MeshEngine>>>>,
    next_handle: AtomicI64,
}

static REGISTRY: LazyLock<EngineRegistry> = LazyLock::new(|| EngineRegistry {
    engines: RwLock::new(HashMap::new()),
    next_handle: AtomicI64::new(1),
});

impl EngineRegistry {
    /// Resolve a Kotlin-held handle to a live engine, or `None` if the handle
    /// is zero, was never issued, or has already been destroyed.
    fn get(&self, handle: jlong) -> Option<Arc<Mutex<MeshEngine>>> {
        if handle == 0 {
            return None;
        }
        self.engines.read().get(&handle).cloned()
    }

    fn insert(&self, engine: MeshEngine) -> jlong {
        let mut next = self.next_handle.fetch_add(1, Ordering::Relaxed);
        // jlong is i64; the counter is monotonic so overflow is unreachable in
        // practice, but skip 0 (the sentinel Kotlin uses for "no engine") and
        // clamp back into range rather than wrapping onto a live handle.
        if next <= 0 {
            next = self.next_handle.fetch_add(1, Ordering::Relaxed).max(1);
        }
        let handle = next as jlong;
        self.engines
            .write()
            .insert(handle, Arc::new(Mutex::new(engine)));
        handle
    }

    /// Drop our reference to the engine. Any in-flight call that already
    /// resolved this handle keeps it alive until it finishes; new calls get
    /// `None`.
    fn remove(&self, handle: jlong) {
        if handle == 0 {
            return;
        }
        self.engines.write().remove(&handle);
    }
}

/// Resolve `handle` and run `f` against the locked engine.
///
/// Returns `None` (without calling `f`) when the handle is dead, so every
/// caller can distinguish "engine destroyed" from "engine returned nothing".
/// Native array marshalling happens *before* this call where relevant, so a
/// JNI exception is raised on a bad argument even if the engine is already
/// gone.
fn with_engine<F, R>(handle: jlong, f: F) -> Option<R>
where
    F: FnOnce(&mut MeshEngine) -> R,
{
    let engine = REGISTRY.get(handle)?;
    let mut guard = engine.lock();
    Some(f(&mut guard))
}

fn jbytearray_to_vec(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    let size = env.get_array_length(array).map_err(|e| e.to_string())? as usize;
    let mut buf = vec![0u8; size];
    // JNI get_byte_array_region expects &mut [i8]; transmute the Vec<u8> buffer
    let buf_slice = unsafe { std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, size) };
    env.get_byte_array_region(array, 0, buf_slice)
        .map_err(|e| e.to_string())?;
    Ok(buf)
}

fn jbytearray_to_array<const N: usize>(
    env: &mut JNIEnv,
    array: &JByteArray,
) -> Result<[u8; N], String> {
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
    storage_path: JString,
) -> jlong {
    let seed_array = match jbytearray_to_array::<32>(&mut env, &seed) {
        Ok(s) => s,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return 0;
        }
    };

    let storage_dir: Option<String> = if storage_path.is_null() {
        None
    } else {
        env.get_string(&storage_path).ok().map(|s| s.into())
    };

    // Real identity/signing provider (seed-derived Ed25519/X25519).
    // Message encryption is handled separately by vodozemac inside SessionManager.
    let crypto = Box::new(rezvan_crypto::SodiumCryptoProvider);
    let mut engine = MeshEngine::new(&seed_array, crypto);

    // Restore prior session material. Every failure mode here is
    // non-fatal by design: a fresh install, a corrupt file, or a state file
    // belonging to a different identity all mean "start clean and re-handshake",
    // which is exactly how the engine behaved before persistence existed.
    // Failing to start the engine instead would be strictly worse.
    if let Some(dir) = storage_dir.as_deref() {
        let path = persistence::state_path(dir);
        match persistence::load(&path, &seed_array) {
            Ok(Some(state)) => match engine.import_state(state) {
                Ok(()) => log::info!("restored mesh engine state from {}", path.display()),
                Err(e) => log::warn!("discarding unparsable mesh engine state: {e}"),
            },
            Ok(None) => log::info!("no prior mesh engine state at {}", path.display()),
            Err(e) => log::warn!("could not read mesh engine state: {e}"),
        }
    }

    REGISTRY.insert(engine)
}

/// Persist the engine's current state to the directory Android passed to
/// `nativeInit`.
///
/// Called periodically (not just on shutdown) because Android gives no
/// reliable "the process is about to die" signal -- a service killed under
/// memory pressure never runs `onDestroy`, so a save that only happens there
/// would lose everything exactly when it matters most.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSaveState(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    storage_path: JString,
    seed: JByteArray,
) -> jboolean {
    let Ok(seed_array) = jbytearray_to_array::<32>(&mut env, &seed) else {
        return 0;
    };
    let Ok(dir) = env.get_string(&storage_path) else {
        return 0;
    };
    let dir: String = dir.into();

    let Some(state) = with_engine(core_ptr, |engine| engine.export_state()) else {
        return 0;
    };
    let state = match state {
        Ok(state) => state,
        Err(e) => {
            log::warn!("could not export mesh engine state: {e}");
            return 0;
        }
    };

    match persistence::save(&persistence::state_path(&dir), &seed_array, &state) {
        Ok(()) => 1,
        Err(e) => {
            log::warn!("could not save mesh engine state: {e}");
            0
        }
    }
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
    let bytes = match jbytearray_to_vec(&mut env, &packet) {
        Ok(b) => b,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let (decrypted_message, actions) = match with_engine(core_ptr, |engine| {
        engine.process_incoming(&bytes, rssi, timestamp_us as u64)
    }) {
        Some(pair) => pair,
        None => return std::ptr::null_mut(),
    };

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
    let actions = match with_engine(core_ptr, |engine| engine.tick()) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };

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

    let actions = match with_engine(core_ptr, |engine| {
        engine.send_message(&recipient, &plain, message_type as u8)
    }) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };
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
    let actions = match with_engine(core_ptr, |engine| {
        engine.send_message_v1(
            &recipient,
            message_id,
            created_at_ms.max(0) as u64,
            message_kind as u8,
            &body,
        )
    }) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };
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
    let actions = match with_engine(core_ptr, |engine| {
        engine.build_received_ack(&original_sender, message_id, created_at_ms.max(0) as u64)
    }) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };
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
    let plain = match jbytearray_to_vec(&mut env, &message) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = match with_engine(core_ptr, |engine| engine.send_broadcast(&plain)) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };
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
    let bundle = match with_engine(core_ptr, |engine| engine.key_bundle()) {
        Some(bundle) => bundle,
        None => return std::ptr::null_mut(),
    };
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
    let node_id = match with_engine(core_ptr, |engine| engine.node_id()) {
        Some(node_id) => node_id,
        None => return std::ptr::null_mut(),
    };
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
    let snapshot = match with_engine(core_ptr, |engine| engine.routing_snapshot()) {
        Some(snapshot) => snapshot,
        None => return std::ptr::null_mut(),
    };
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
    let peer = match jbytearray_to_array::<8>(&mut env, &peer_id) {
        Ok(p) => p,
        Err(_) => return 0,
    };
    let b = match jbytearray_to_vec(&mut env, &bundle) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    with_engine(core_ptr, |engine| engine.register_peer_keys(&peer, &b)).unwrap_or(false)
        as jboolean
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
    let key = match with_engine(core_ptr, |engine| {
        engine.create_channel_key(channel_id as u32)
    }) {
        Some(key) => key,
        None => return std::ptr::null_mut(),
    };
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
    let k = match jbytearray_to_array::<32>(&mut env, &key) {
        Ok(k) => k,
        Err(_) => return 0,
    };
    with_engine(core_ptr, |engine| {
        engine.set_channel_key(channel_id as u32, k)
    });
    1
}

/// Revoke membership of a channel by dropping its sender key.
///
/// Returns true if a key was held and removed. The Android side calls this
/// when the user leaves a channel: clearing the database row alone would leave
/// the engine able to decrypt that channel for the rest of the process's life,
/// and the next engine-state save would write the key straight back out.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeRemoveChannelKey(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    channel_id: jint,
) -> jboolean {
    with_engine(core_ptr, |engine| {
        engine.remove_channel_key(channel_id as u32)
    })
    .unwrap_or(false) as jboolean
}

/// Channel ids the engine currently holds a sender key for, as a packed
/// big-endian `u32` list.
///
/// The service uses this to reconcile against the authoritative database on
/// start-up: anything still held here but absent from the database is a channel
/// the user has left, and must be revoked rather than silently retained.
///
/// Sorted ascending, so the payload is deterministic.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetChannelKeyIds(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let ids = match with_engine(core_ptr, |engine| engine.channel_key_ids()) {
        Some(ids) => ids,
        None => return std::ptr::null_mut(),
    };
    let mut packed = Vec::with_capacity(ids.len() * 4);
    for id in ids {
        packed.extend_from_slice(&id.to_be_bytes());
    }
    vec_to_jbytearray(&mut env, &packed).unwrap_or(std::ptr::null_mut())
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
    let plain = match jbytearray_to_vec(&mut env, &message) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = match with_engine(core_ptr, |engine| {
        engine.send_channel_message(channel_id as u32, &plain)
    }) {
        Some(actions) => actions,
        None => return std::ptr::null_mut(),
    };
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
    with_engine(core_ptr, |engine| engine.get_power_state() as jint).unwrap_or(-1)
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
    with_engine(core_ptr, |engine| {
        engine.set_user_override(power_state_from_i32(state))
    });
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeClearPowerOverride(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    with_engine(core_ptr, |engine| engine.set_user_override(None));
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeUpdateBattery(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    level_percent: jint,
    is_charging: jboolean,
) {
    with_engine(core_ptr, |engine| {
        engine.update_battery(level_percent as u8, is_charging != 0)
    });
}

#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    REGISTRY.remove(core_ptr);
}
#[cfg(test)]
mod registry_tests {
    use super::*;
    use rezvan_crypto::SodiumCryptoProvider;
    use std::sync::atomic::AtomicUsize;
    use std::sync::Arc;

    /// Regression test for the original JNI data race.
    ///
    /// Every JNI entry point used to build its own `&mut MeshEngine` from the
    /// raw `jlong`, so the service's tick loop, its packet coroutines, and the
    /// Bluetooth callback threads could all be inside `MeshEngine` at once --
    /// undefined behaviour that rustc cannot catch but that corrupts Olm
    /// ratchet state in practice.
    ///
    /// The engine now lives behind a `Mutex` in a registry, so hammering the
    /// full set of entry points from many threads at once must be safe. Run
    /// under `cargo test` (and ideally a sanitizer build) this at least proves
    /// the locking discipline is wired up everywhere; the real proof needs
    /// ThreadSanitizer, since a missing lock in ordinary execution often
    /// appears to work.
    #[test]
    fn concurrent_entry_points_are_serialized() {
        let handle = REGISTRY.insert(MeshEngine::new(&[5u8; 32], Box::new(SodiumCryptoProvider)));
        assert_ne!(handle, 0, "a live engine must never be issued handle 0");

        let calls = Arc::new(AtomicUsize::new(0));
        let mut handles = Vec::new();
        for _ in 0..8 {
            handles.push(std::thread::spawn({
                let handle = handle;
                let calls = Arc::clone(&calls);
                move || {
                    for _ in 0..50 {
                        // A representative slice of the mutating entry points:
                        // tick, inbound packet processing, send, channel key
                        // ops, power/battery updates, and read-only snapshots.
                        with_engine(handle, |e| e.tick()).unwrap();
                        with_engine(handle, |e| {
                            e.process_incoming(&[], -50, 0);
                        })
                        .unwrap();
                        with_engine(handle, |e| e.send_broadcast(b"hi")).unwrap();
                        with_engine(handle, |e| e.routing_snapshot()).unwrap();
                        with_engine(handle, |e| e.get_power_state() as i32).unwrap();
                        with_engine(handle, |e| e.update_battery(50, false)).unwrap();
                        with_engine(handle, |e| {
                            e.set_channel_key(1, [7u8; 32]);
                        })
                        .unwrap();
                        with_engine(handle, |e| e.key_bundle()).unwrap();
                        calls.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }));
        }
        for h in handles {
            h.join().expect("worker thread panicked -- lock poisoned?");
        }
        assert_eq!(calls.load(Ordering::Relaxed), 8 * 50);

        // The engine must still be usable and consistent afterwards.
        let tick = with_engine(handle, |e| e.snapshot_tick()).unwrap();
        // 8 threads x 50 iterations, minus the ticks that were skipped because
        // the power state suppressed advertising. Just assert it moved.
        assert!(tick > 0, "ticks should have advanced across the workers");

        REGISTRY.remove(handle);
    }

    /// `nativeDestroy` may run while other threads are still inside calls. The
    /// `Arc` in the registry is what makes that safe: an in-flight caller
    /// keeps the engine alive, and a *new* lookup after removal must fail
    /// cleanly instead of dereferencing freed memory.
    #[test]
    fn destroy_while_in_use_does_not_free_under_a_caller() {
        let handle = REGISTRY.insert(MeshEngine::new(&[6u8; 32], Box::new(SodiumCryptoProvider)));

        let worker = std::thread::spawn({
            let handle = handle;
            move || {
                for _ in 0..200 {
                    if with_engine(handle, |e| e.tick()).is_none() {
                        // Destroyed mid-flight: a clean None, not a crash.
                        return true;
                    }
                    std::thread::yield_now();
                }
                false
            }
        });

        std::thread::sleep(std::time::Duration::from_millis(2));
        REGISTRY.remove(handle);

        let saw_removal = worker.join().expect("worker panicked on a freed engine");
        assert!(
            saw_removal,
            "the worker should eventually observe removal rather than spin forever"
        );

        // Every post-removal lookup must be None, and must stay None.
        assert!(with_engine(handle, |e| e.tick()).is_none());
        // Removing twice is what onDestroy-after-a-crash looks like; it must be
        // a harmless no-op rather than a double free.
        REGISTRY.remove(handle);
    }

    #[test]
    fn handle_zero_is_never_resolvable() {
        assert!(with_engine(0, |e| e.tick()).is_none());
        REGISTRY.remove(0); // must not panic
    }

    #[test]
    fn handles_are_unique() {
        let a = REGISTRY.insert(MeshEngine::new(&[1u8; 32], Box::new(SodiumCryptoProvider)));
        let b = REGISTRY.insert(MeshEngine::new(&[2u8; 32], Box::new(SodiumCryptoProvider)));
        assert_ne!(a, b);
        assert!(with_engine(a, |e| e.tick()).is_some());
        assert!(with_engine(b, |e| e.tick()).is_some());

        // Destroying one must not disturb the other.
        REGISTRY.remove(a);
        assert!(with_engine(a, |e| e.tick()).is_none());
        assert!(with_engine(b, |e| e.tick()).is_some());
        REGISTRY.remove(b);
    }
}
