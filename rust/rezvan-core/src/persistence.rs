//! Durable, authenticated storage for the engine's session state.
//!
//! # Why this exists
//!
//! `MeshEngine` used to be constructed fresh on every `nativeInit` and thrown
//! away on `nativeDestroy`. Everything it owned lived only in RAM:
//!
//! - the Olm `Account` (so every restart published a *new* Olm identity key and
//!   one-time-key batch, invalidating what peers had stored),
//! - every established Double Ratchet `Session`,
//! - every peer's advertised key bundle (`peer_keys`),
//! - every channel key,
//! - the network-wide beacon epoch key and its number,
//! - the routing table, including the replay-sequence history that stops a
//!   captured old beacon being replayed after its route is purged.
//!
//! `nativeInit` even accepted a `storage_path` argument and ignored it. The
//! practical effect was that after any service restart, process death, or OS
//! reclaim, direct messages to already-known contacts silently failed to
//! decrypt until each peer happened to send a fresh KeyAnnouncement (which is
//! every 3rd beacon), and channel membership silently evaporated because the
//! shared channel keys were gone.
//!
//! # Format
//!
//! ```text
//! "RZVS" | version:u8 | nonce:24 | XChaCha20-Poly1305(nonce' || ciphertext)
//! ```
//!
//! The plaintext is a JSON document; the whole thing is sealed under a key
//! derived from the device identity seed (see `rezvan_crypto::secure_store`),
//! so the file is not usable on its own if it is copied off the device.
//!
//! Writes go to a temporary file in the same directory and are then renamed
//! over the target. Rename is atomic within a filesystem, so a crash or battery
//! pull mid-save leaves the *previous* good state intact rather than a
//! truncated file that would fail to decrypt and force a full re-handshake.
//!
//! # Failure policy
//!
//! A missing, unreadable, or undecryptable state file is **not** an error: the
//! engine starts with empty state, exactly as it behaved before this module
//! existed. Losing history degrades to a re-handshake; failing to start does
//! not. A file that decrypts but fails to parse is logged distinctly, because
//! that one *is* suspicious (wrong-key or tampered state that happened to pass
//! AEAD is not possible, so it means corruption between seal and parse).

use std::fs;
use std::io::Write as _;
use std::path::{Path, PathBuf};

use rezvan_crypto::secure_store;

/// File magic, so a truncated or foreign file is rejected before we even try
/// to derive a nonce out of it.
const MAGIC: &[u8; 4] = b"RZVS";
/// On-disk format version. Bump on any incompatible layout change; older
/// versions are then treated as "no state" rather than misparsed.
const FORMAT_VERSION: u8 = 1;

/// The full set of engine state, in serializable form.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PersistedEngineState {
    pub session: crate::session::PersistedSessionState,
    pub routing: crate::routing::PersistedRoutingState,
    pub ogm_sequence: u32,
    pub adv_sequence: u32,
}

#[derive(Debug)]
pub enum PersistError {
    Io(std::io::Error),
    Serialize(String),
    Deserialize(String),
}

impl std::fmt::Display for PersistError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PersistError::Io(e) => write!(f, "io: {e}"),
            PersistError::Serialize(e) => write!(f, "serialize: {e}"),
            PersistError::Deserialize(e) => write!(f, "deserialize: {e}"),
        }
    }
}

/// Resolve the state file path inside the directory Android handed us.
///
/// `storage_path` is the service's `filesDir`; the state file lives directly
/// inside it rather than in a subdirectory so there is nothing to create
/// before the first save.
pub fn state_path(storage_path: &str) -> PathBuf {
    Path::new(storage_path).join("mesh_engine_state.bin")
}

/// Serialize and seal `state`, writing it atomically to `path`.
///
/// The temporary file is created in the same directory as the target so the
/// final `rename` stays within one filesystem and is therefore atomic.
pub fn save(
    path: &Path,
    seed: &[u8; 32],
    state: &PersistedEngineState,
) -> Result<(), PersistError> {
    let json = serde_json::to_vec(state).map_err(|e| PersistError::Serialize(e.to_string()))?;
    let sealed = secure_store::seal_state(&secure_store::derive_state_key(seed), &json);

    let mut blob = Vec::with_capacity(MAGIC.len() + 1 + sealed.len());
    blob.extend_from_slice(MAGIC);
    blob.push(FORMAT_VERSION);
    blob.extend_from_slice(&sealed);

    // The temp file goes in the same directory as the target so the final
    // rename stays within one filesystem, which is what makes it atomic.
    let tmp = path.with_extension("bin.tmp");

    {
        let mut file = fs::File::create(&tmp).map_err(PersistError::Io)?;
        file.write_all(&blob).map_err(PersistError::Io)?;
        // Force the bytes out before the rename, otherwise a crash can leave a
        // correctly-named but empty file after the rename completes.
        file.sync_all().map_err(PersistError::Io)?;
    }

    fs::rename(&tmp, path).map_err(PersistError::Io)?;
    Ok(())
}

/// Load, decrypt and parse the state file at `path`.
///
/// `Ok(None)` means "no usable state" and is the expected outcome on a fresh
/// install, a first run, or after a device restore. `Err` is reserved for
/// failures that are worth surfacing (unexpected I/O, or a decrypted payload
/// that does not parse).
pub fn load(path: &Path, seed: &[u8; 32]) -> Result<Option<PersistedEngineState>, PersistError> {
    let blob = match fs::read(path) {
        Ok(blob) => blob,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(PersistError::Io(e)),
    };

    if blob.len() < MAGIC.len() + 1 {
        // Too short to even contain a header -- treat as absent rather than
        // erroring, so a garbage file degrades to a re-handshake.
        return Ok(None);
    }
    if &blob[..MAGIC.len()] != MAGIC {
        return Ok(None);
    }
    if blob[MAGIC.len()] != FORMAT_VERSION {
        // A future/older format. Same reasoning: start clean rather than guess.
        return Ok(None);
    }

    let sealed = &blob[MAGIC.len() + 1..];
    let plaintext = match secure_store::open_state(&secure_store::derive_state_key(seed), sealed) {
        Some(plaintext) => plaintext,
        // Wrong seed, tampered file, or truncated ciphertext. Indistinguishable
        // by design -- all three are "we don't have valid state".
        None => return Ok(None),
    };

    match serde_json::from_slice::<PersistedEngineState>(&plaintext) {
        Ok(state) => Ok(Some(state)),
        Err(e) => Err(PersistError::Deserialize(e.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::MeshEngine;
    use rezvan_crypto::SodiumCryptoProvider;

    fn export(engine: &MeshEngine) -> PersistedEngineState {
        engine.export_state().expect("export_state")
    }

    fn temp_dir(tag: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rezvan-persist-test-{tag}-{}", std::process::id()));
        let _ = fs::create_dir_all(&dir);
        dir
    }

    #[test]
    fn save_load_roundtrip_preserves_session_state() {
        let dir = temp_dir("roundtrip");
        let path = state_path(dir.to_str().unwrap());
        let seed = [42u8; 32];

        let mut engine = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        let channel_key = engine.create_channel_key(7);
        let state = export(&engine);
        save(&path, &seed, &state).expect("save");

        let loaded = load(&path, &seed).expect("load").expect("state present");
        assert_eq!(loaded.session.channel_keys, vec![(7u32, channel_key)]);
        assert_eq!(loaded.routing.current_tick, state.routing.current_tick);
        assert_eq!(loaded.ogm_sequence, state.ogm_sequence);
        assert_eq!(loaded.adv_sequence, state.adv_sequence);

        // The account pickle must round-trip to the SAME Olm identity key --
        // this is the whole point: peers' stored bundles must stay valid.
        let before = engine.key_bundle();
        let mut restored = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        restored.import_state(loaded).expect("import");
        let after = restored.key_bundle();
        assert_eq!(
            &before[..32],
            &after[..32],
            "Olm identity key must survive a restart, or every peer's stored bundle is orphaned"
        );
        assert_eq!(restored.channel_key(7), Some(channel_key));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_is_not_an_error() {
        let dir = temp_dir("missing");
        let path = state_path(dir.to_str().unwrap());
        assert!(load(&path, &[1u8; 32]).expect("load").is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn wrong_seed_yields_no_state() {
        let dir = temp_dir("wrongseed");
        let path = state_path(dir.to_str().unwrap());
        let engine = MeshEngine::new(&[5u8; 32], Box::new(SodiumCryptoProvider));
        save(&path, &[5u8; 32], &export(&engine)).expect("save");

        // Same path, different device identity: must NOT decrypt, and must not
        // be reported as a parse error either -- just "no state".
        assert!(load(&path, &[6u8; 32]).expect("load").is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn tampered_file_is_rejected() {
        let dir = temp_dir("tamper");
        let path = state_path(dir.to_str().unwrap());
        let seed = [9u8; 32];
        let engine = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        save(&path, &seed, &export(&engine)).expect("save");

        let mut blob = fs::read(&path).expect("read");
        let last = blob.len() - 1;
        blob[last] ^= 0xff;
        fs::write(&path, &blob).expect("write");

        assert!(load(&path, &seed).expect("load").is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn foreign_magic_and_version_are_treated_as_absent() {
        let dir = temp_dir("magic");
        let path = state_path(dir.to_str().unwrap());
        let seed = [3u8; 32];
        let engine = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        let state = export(&engine);

        save(&path, &seed, &state).expect("save");
        let good = fs::read(&path).expect("read");

        // Wrong magic.
        let mut bad = good.clone();
        bad[0] = b'X';
        fs::write(&path, &bad).expect("write");
        assert!(load(&path, &seed).expect("load").is_none());

        // Right magic, unknown version.
        let mut bad = good.clone();
        bad[MAGIC.len()] = FORMAT_VERSION.wrapping_add(1);
        fs::write(&path, &bad).expect("write");
        assert!(load(&path, &seed).expect("load").is_none());

        // Truncated below a header.
        fs::write(&path, b"RZ").expect("write");
        assert!(load(&path, &seed).expect("load").is_none());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_leaves_no_temp_file_behind() {
        let dir = temp_dir("notmp");
        let path = state_path(dir.to_str().unwrap());
        let seed = [11u8; 32];
        let engine = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        save(&path, &seed, &export(&engine)).expect("save");
        assert!(!path.with_extension("bin.tmp").exists());
        assert!(path.exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn routing_state_preserves_replay_history() {
        let dir = temp_dir("routing");
        let path = state_path(dir.to_str().unwrap());
        let seed = [17u8; 32];
        let mut engine = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        for _ in 0..3 {
            engine.tick();
        }
        let state = export(&engine);
        save(&path, &seed, &state).expect("save");
        let loaded = load(&path, &seed).expect("load").expect("present");

        // Round-tripping routing must be lossless.
        assert_eq!(loaded.routing.routes.len(), state.routing.routes.len());
        assert_eq!(
            loaded.routing.last_beacon_seq, state.routing.last_beacon_seq,
            "replay-sequence history must survive a restart or old beacons become replayable"
        );
        assert_eq!(loaded.routing.last_packet_seq, state.routing.last_packet_seq);
        assert_eq!(loaded.routing.current_tick, state.routing.current_tick);
        assert_eq!(loaded.routing.relayed_seen, state.routing.relayed_seen);

        let mut restored = MeshEngine::new(&seed, Box::new(SodiumCryptoProvider));
        restored.import_state(loaded.clone()).expect("import_state");
        assert_eq!(
            restored.snapshot_tick(),
            state.routing.current_tick,
            "import must not reset the logical clock, or purge_stale pins dead routes"
        );
        // Re-exporting after import must be a fixed point: nothing is invented
        // or lost by the round trip.
        let re_exported = export(&restored);
        assert_eq!(
            re_exported.routing.current_tick,
            loaded.routing.current_tick
        );
        assert_eq!(re_exported.routing.routes, loaded.routing.routes);
        assert_eq!(
            re_exported.routing.last_beacon_seq,
            loaded.routing.last_beacon_seq
        );
        assert_eq!(
            re_exported.routing.last_packet_seq,
            loaded.routing.last_packet_seq
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn deserialization_failure_is_reported_not_swallowed() {
        // A file that decrypts but doesn't parse is reachable only by a
        // same-key writer bug, but it must surface as Err rather than being
        // silently reported as "no state".
        let dir = temp_dir("parse");
        let path = state_path(dir.to_str().unwrap());
        let seed = [23u8; 32];
        let sealed =
            secure_store::seal_state(&secure_store::derive_state_key(&seed), b"this is not json");
        let mut blob = Vec::new();
        blob.extend_from_slice(MAGIC);
        blob.push(FORMAT_VERSION);
        blob.extend_from_slice(&sealed);
        fs::write(&path, &blob).expect("write");

        assert!(matches!(
            load(&path, &seed),
            Err(PersistError::Deserialize(_))
        ));
        let _ = fs::remove_dir_all(&dir);
    }
}
