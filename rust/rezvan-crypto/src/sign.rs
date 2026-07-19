use sodiumoxide::crypto::sign;
use crate::identity::IdentityKeypair;

pub fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
    let sk = sign::SecretKey(identity.private_ed25519);
    let sig = sign::sign_detached(message, &sk);
    let mut result = [0u8; 64];
    result.copy_from_slice(sig.as_ref());
    result
}

pub fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
    let pk = sign::PublicKey(*public_key);
    let sig = match sign::Signature::from_bytes(signature) {
        Ok(s) => s,
        Err(_) => return false,
    };
    sign::verify_detached(&sig, message, &pk)
}
