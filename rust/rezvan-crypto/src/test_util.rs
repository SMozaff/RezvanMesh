//! Test-only helpers, shared across the crate's unit tests.
//!
//! Kept behind `#[cfg(test)]` so none of this reaches a release build.

/// Decode a lowercase hex string into bytes.
///
/// Panics on odd length or a non-hex character, so a malformed test constant
/// fails loudly at the point of the mistake rather than silently producing the
/// wrong length and failing an assertion somewhere less obvious.
pub fn hex(s: &str) -> Vec<u8> {
    assert!(
        s.len().is_multiple_of(2),
        "hex string must have even length: {s:?}"
    );
    (0..s.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&s[i..i + 2], 16)
                .unwrap_or_else(|_| panic!("invalid hex at byte {i} in {s:?}"))
        })
        .collect()
}

/// Decode a hex string that must be exactly `N` bytes.
pub fn hex_array<const N: usize>(s: &str) -> [u8; N] {
    hex(s)
        .try_into()
        .unwrap_or_else(|v: Vec<u8>| panic!("expected {N} bytes ({N} hex chars), got {}", v.len()))
}
