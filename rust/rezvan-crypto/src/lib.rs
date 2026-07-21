pub mod identity;
pub mod sign;
pub mod sender_key;
pub mod hkdf;
pub mod beacon_mac;

pub use identity::IdentityKeypair;

// NOTE: the hand-rolled Double Ratchet SessionState that used to live here
// has been removed. Session/ratchet state is now owned entirely by
// `vodozemac` and managed in `rezvan-core::session` (see remediation #4:
// dead code from the pre-vodozemac design was misleading to readers).

#[derive(Debug)]
pub enum CryptoError {
    HandshakeFailed,
    DecryptionFailed,
    InvalidKey,
    NoSession,
    MessageOutOfOrder,
}

pub trait CryptoProvider: Send + Sync {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair;
    fn sign(&self, identity: &IdentityKeypair, message: &[u8]) -> [u8; 64];
    fn verify(&self, public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool;

    fn generate_sender_key(&self) -> [u8; 32];
    /// Encrypts AND signs with the sender's own Ed25519 identity key so
    /// receivers can verify who actually sent it (see sender_key.rs module
    /// docs -- security audit finding #10). `sender_identity` is the
    /// caller's own identity, not the recipient's.
    fn sender_key_encrypt(&self, key: &[u8; 32], plaintext: &[u8], sender_identity: &IdentityKeypair) -> Vec<u8>;
    /// Decrypts and verifies against `expected_sender_pubkey`, which MUST be
    /// the caller's own independently-known record of that member's identity
    /// (not read from the wire and trusted blindly -- see sender_key.rs docs).
    fn sender_key_decrypt(&self, key: &[u8; 32], expected_sender_pubkey: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>>;

    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8>;

    fn random_bytes(&self, len: usize) -> Vec<u8>;

    fn clone_box(&self) -> Box<dyn CryptoProvider>;
}

pub struct SodiumCryptoProvider;

impl CryptoProvider for SodiumCryptoProvider {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair {
        identity::generate_identity(seed)
    }
    fn sign(&self, id: &IdentityKeypair, msg: &[u8]) -> [u8; 64] {
        sign::sign(id, msg)
    }
    fn verify(&self, pk: &[u8; 32], msg: &[u8], sig: &[u8; 64]) -> bool {
        sign::verify(pk, msg, sig)
    }
    fn generate_sender_key(&self) -> [u8; 32] {
        sender_key::generate()
    }
    fn sender_key_encrypt(&self, key: &[u8; 32], pt: &[u8], sender_identity: &IdentityKeypair) -> Vec<u8> {
        sender_key::encrypt(key, pt, sender_identity)
    }
    fn sender_key_decrypt(&self, key: &[u8; 32], expected_sender_pubkey: &[u8; 32], ct: &[u8]) -> Option<Vec<u8>> {
        sender_key::decrypt(key, expected_sender_pubkey, ct)
    }
    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], len: usize) -> Vec<u8> {
        hkdf::hkdf_sha256(ikm, salt, info, len)
    }
    fn random_bytes(&self, len: usize) -> Vec<u8> {
        sodiumoxide::randombytes::randombytes(len)
    }
    fn clone_box(&self) -> Box<dyn CryptoProvider> {
        Box::new(SodiumCryptoProvider)
    }
}

#[cfg(test)]
mod olm_regression_test {
    use vodozemac::olm::{Account, OlmMessage, SessionConfig};

    #[test]
    fn olm_two_party_roundtrip() {
        let alice = Account::new();
        let mut bob = Account::new();

        bob.generate_one_time_keys(1);
        let bob_otk = *bob
            .one_time_keys()
            .values()
            .next()
            .expect("Bob should have a one-time key");

        let mut alice_session = alice
            .create_outbound_session(
                SessionConfig::version_1(),
                bob.curve25519_key(),
                bob_otk,
            )
            .expect("outbound session");

        bob.mark_keys_as_published();

        let plaintext = b"emergency: bridge is down";
        let olm_msg = alice_session.encrypt(plaintext).expect("encrypt");

        let prekey = match olm_msg {
            OlmMessage::PreKey(m) => m,
            OlmMessage::Normal(_) => panic!("first message must be a pre-key message"),
        };

        let result = bob
            .create_inbound_session(
                SessionConfig::version_1(),
                alice.curve25519_key(),
                &prekey,
            )
            .expect("Bob should create an inbound session from Alice's pre-key");

        assert_eq!(
            result.plaintext, plaintext,
            "Bob must decrypt exactly what Alice sent"
        );
    }
}
