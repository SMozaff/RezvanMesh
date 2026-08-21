// rezvan-common/src/lib.rs

use sha2::{Sha256, Digest};

pub type NodeId = [u8; 8];
pub type MessageId = [u8; 16];

/// Gate 1 direct-message acknowledgement packet type. The outer packet is
/// signed; its payload is encrypted with the existing pairwise session.
pub const PACKET_TYPE_MESSAGE_ACK: u8 = 0x07;
pub const DIRECT_ENVELOPE_MAGIC: [u8; 2] = *b"RM";
pub const DIRECT_ENVELOPE_VERSION: u8 = 0x01;
pub const ACK_ENVELOPE_MAGIC: [u8; 2] = *b"RA";
pub const ACK_ENVELOPE_VERSION: u8 = 0x01;
pub const ACK_CODE_RECEIVED: u8 = 0x01;
pub const CAPABILITY_FORMAT_VERSION: u8 = 0x01;
pub const CAP_MESSAGE_ID_AND_ACK: u32 = 1;

/// Encrypted payload carried by a Gate 1 direct-message packet. The message
/// identity is inside the existing Olm ciphertext so relays cannot correlate
/// messages by identifier.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectMessageEnvelopeV1 {
    pub message_kind: u8,
    pub message_id: MessageId,
    pub created_at_ms: u64,
    pub body: Vec<u8>,
}

impl DirectMessageEnvelopeV1 {
    pub const HEADER_SIZE: usize = 32;

    pub fn serialize(&self) -> Option<Vec<u8>> {
        if self.message_kind != 0 || self.message_id == [0u8; 16] {
            return None;
        }
        let body_len: u32 = self.body.len().try_into().ok()?;
        let mut out = Vec::with_capacity(Self::HEADER_SIZE + self.body.len());
        out.extend_from_slice(&DIRECT_ENVELOPE_MAGIC);
        out.push(DIRECT_ENVELOPE_VERSION);
        out.push(self.message_kind);
        out.extend_from_slice(&self.message_id);
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&body_len.to_be_bytes());
        out.extend_from_slice(&self.body);
        Some(out)
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::HEADER_SIZE || data[0..2] != DIRECT_ENVELOPE_MAGIC {
            return None;
        }
        if data[2] != DIRECT_ENVELOPE_VERSION || data[3] != 0 {
            return None;
        }
        let mut message_id = [0u8; 16];
        message_id.copy_from_slice(&data[4..20]);
        if message_id == [0u8; 16] {
            return None;
        }
        let created_at_ms = u64::from_be_bytes(data[20..28].try_into().ok()?);
        let body_len = u32::from_be_bytes(data[28..32].try_into().ok()?) as usize;
        if data.len() != Self::HEADER_SIZE.checked_add(body_len)? {
            return None;
        }
        let body = data[32..].to_vec();
        std::str::from_utf8(&body).ok()?;
        Some(Self { message_kind: 0, message_id, created_at_ms, body })
    }
}

/// Exact encrypted acknowledgement payload for a persisted direct message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageAckEnvelopeV1 {
    pub message_id: MessageId,
    pub original_sender: NodeId,
    pub original_recipient: NodeId,
    pub created_at_ms: u64,
}

impl MessageAckEnvelopeV1 {
    pub const SIZE: usize = 44;

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(Self::SIZE);
        out.extend_from_slice(&ACK_ENVELOPE_MAGIC);
        out.push(ACK_ENVELOPE_VERSION);
        out.push(ACK_CODE_RECEIVED);
        out.extend_from_slice(&self.message_id);
        out.extend_from_slice(&self.original_sender);
        out.extend_from_slice(&self.original_recipient);
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() != Self::SIZE || data[0..2] != ACK_ENVELOPE_MAGIC ||
            data[2] != ACK_ENVELOPE_VERSION || data[3] != ACK_CODE_RECEIVED {
            return None;
        }
        let mut message_id = [0u8; 16];
        message_id.copy_from_slice(&data[4..20]);
        if message_id == [0u8; 16] {
            return None;
        }
        let mut original_sender = [0u8; 8];
        original_sender.copy_from_slice(&data[20..28]);
        let mut original_recipient = [0u8; 8];
        original_recipient.copy_from_slice(&data[28..36]);
        let created_at_ms = u64::from_be_bytes(data[36..44].try_into().ok()?);
        Some(Self { message_id, original_sender, original_recipient, created_at_ms })
    }
}

pub fn compute_node_id(public_key: &[u8; 32]) -> NodeId {
    let hash = Sha256::digest(public_key);
    let mut node_id = [0u8; 8];
    node_id.copy_from_slice(&hash[0..8]);
    node_id
}

// rezvan-core/src/engine.rs (not compiled from this crate, but documented
// here since it owns the wire format): packet types 0x01 (full OGM, if ever
// sent over GATT), 0x03 (emergency broadcast), 0x04 (handshake), and 0x05
// (KeyAnnouncement) now carry a 64-byte Ed25519 signature APPENDED AFTER
// the payload -- i.e. the full wire packet is
// [MeshPacketHeader:26][payload:payload_len][signature:64].
// Packet type 0x02 (direct messages) is NOT separately signed: it's already
// authenticated by the Olm session's AEAD (see session.rs), and re-signing
// on top of an already-authenticated ciphertext buys nothing.
//
// VERSION 0x03 (relay/multi-hop support): bumped from 0x02. Adds an explicit
// `destination` field to MeshPacketHeader (see below) so an intermediate
// relay node can tell who a packet is ultimately FOR, distinct from
// `originator` (who created it) and `next_hop` (who to physically forward it
// to on this hop). Without this field, relay was architecturally impossible:
// there was no way for a node that isn't the final recipient to know where
// to forward a 0x02/0x03/0x06 packet. Pre-1.0 beta, so this is again a clean
// break like the 0x01->0x02 bump, not a negotiated upgrade.
pub const MESH_PACKET_VERSION: u8 = 0x03;
pub const MESH_PACKET_SIGNATURE_LEN: usize = 64;

/// Sentinel `destination`/`next_hop` value meaning "broadcast to the whole
/// mesh" (used by 0x03 emergency broadcasts, 0x05 KeyAnnouncements, and 0x01
/// OGMs -- all all-zero NodeId, matching the existing convention already
/// used for `Action::SendBlePacket`'s broadcast target in action.rs).
pub const BROADCAST_DESTINATION: NodeId = [0u8; 8];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MeshPacketHeader {
    pub version: u8,
    pub packet_type: u8,
    pub ttl: u8,
    pub originator: NodeId,
    /// Final intended recipient of this packet, distinct from `originator`
    /// (who created it) and `next_hop` (who to forward it to on just this
    /// hop). `BROADCAST_DESTINATION` (all-zero) means "everyone" -- used by
    /// packet types that are inherently mesh-wide (0x01 OGM, 0x03 emergency
    /// broadcast, 0x05 KeyAnnouncement). Added in version 0x03 to make
    /// multi-hop relay possible at all: a relaying node needs to know who a
    /// packet is ultimately for in order to decide where to forward it.
    pub destination: NodeId,
    pub sequence: u32,
    pub hop_count: u8,
    pub next_hop: NodeId,
    pub payload_len: u16,
}

impl MeshPacketHeader {
    pub const SIZE: usize = 34;

    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIZE);
        buf.push(self.version);
        buf.push(self.packet_type);
        buf.push(self.ttl);
        buf.extend_from_slice(&self.originator);
        buf.extend_from_slice(&self.destination);
        buf.extend_from_slice(&self.sequence.to_be_bytes());
        buf.push(self.hop_count);
        buf.extend_from_slice(&self.next_hop);
        buf.extend_from_slice(&self.payload_len.to_be_bytes());
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE { return None; }
        let version = data[0];
        let packet_type = data[1];
        let ttl = data[2];
        let mut originator = [0u8; 8];
        originator.copy_from_slice(&data[3..11]);
        let mut destination = [0u8; 8];
        destination.copy_from_slice(&data[11..19]);
        let sequence = u32::from_be_bytes([data[19], data[20], data[21], data[22]]);
        let hop_count = data[23];
        let mut next_hop = [0u8; 8];
        next_hop.copy_from_slice(&data[24..32]);
        let payload_len = u16::from_be_bytes([data[32], data[33]]);
        Some(Self { version, packet_type, ttl, originator, destination, sequence, hop_count, next_hop, payload_len })
    }
}

const _: () = assert!(MeshPacketHeader::SIZE == 34);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OGMPayload {
    pub timestamp: u64,
    pub link_quality: u8,
    pub path_metric: u32,
    pub neighbor_count: u8,
    pub neighbors: [NeighborInfo; 9],
}

impl OGMPayload {
    pub const SIZE: usize = 50;

    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIZE);
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(self.link_quality);
        buf.extend_from_slice(&self.path_metric.to_be_bytes());
        buf.push(self.neighbor_count);
        for n in &self.neighbors {
            buf.extend_from_slice(&n.serialize());
        }
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE { return None; }
        let timestamp = u64::from_be_bytes([data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7]]);
        let link_quality = data[8];
        let path_metric = u32::from_be_bytes([data[9], data[10], data[11], data[12]]);
        let neighbor_count = data[13];
        let mut neighbors = [NeighborInfo::default(); 9];
        for i in 0..9 {
            let off = 14 + i * 4;
            neighbors[i] = NeighborInfo::deserialize(&data[off..off+4])?;
        }
        Some(Self { timestamp, link_quality, path_metric, neighbor_count, neighbors })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct NeighborInfo {
    pub node_id_prefix: [u8; 3],
    pub link_quality: u8,
}

impl NeighborInfo {
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(4);
        buf.extend_from_slice(&self.node_id_prefix);
        buf.push(self.link_quality);
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 4 { return None; }
        let mut node_id_prefix = [0u8; 3];
        node_id_prefix.copy_from_slice(&data[0..3]);
        let link_quality = data[3];
        Some(Self { node_id_prefix, link_quality })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecryptedMessage {
    pub conversation_id: [u8; 16],
    pub sender_id: NodeId,
    pub timestamp: u64,
    pub message_type: u8,
    /// Present only for Gate 1 direct messages. Legacy messages deliberately
    /// remain without a protocol identity and cannot generate acknowledgements.
    pub protocol_message_id: Option<MessageId>,
    pub content: Vec<u8>,
}

impl DecryptedMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let content_len = (self.content.len() as u32).to_be_bytes();
        let mut buf = Vec::with_capacity(16 + 8 + 8 + 1 + 4 + self.content.len());
        buf.extend_from_slice(&self.conversation_id);
        buf.extend_from_slice(&self.sender_id);
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(self.message_type);
        buf.extend_from_slice(&content_len);
        buf.extend_from_slice(&self.content);
        match self.protocol_message_id {
            Some(id) => {
                buf.push(1);
                buf.extend_from_slice(&id);
            }
            None => buf.push(0),
        }
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 16 + 8 + 8 + 1 + 4 { return None; }
        let mut conversation_id = [0u8; 16];
        conversation_id.copy_from_slice(&data[0..16]);
        let mut sender_id = [0u8; 8];
        sender_id.copy_from_slice(&data[16..24]);
        let timestamp = u64::from_be_bytes([data[24], data[25], data[26], data[27], data[28], data[29], data[30], data[31]]);
        let message_type = data[32];
        let content_len = u32::from_be_bytes([data[33], data[34], data[35], data[36]]) as usize;
        if data.len() < 37 + content_len + 1 { return None; }
        let content_end = 37 + content_len;
        let content = data[37..content_end].to_vec();
        let id_present = data[content_end];
        let protocol_message_id = match id_present {
            0 if data.len() == content_end + 1 => None,
            1 if data.len() == content_end + 17 => {
                let mut id = [0u8; 16];
                id.copy_from_slice(&data[content_end + 1..content_end + 17]);
                Some(id)
            }
            _ => return None,
        };
        Some(Self { conversation_id, sender_id, timestamp, message_type, protocol_message_id, content })
    }
}

// ============================================================================
// AdvBeaconExt — 24-byte BLE legacy advertisement beacon
// ============================================================================
// WHY A SEPARATE STRUCT:
//   MeshPacketHeader is 26 bytes. Legacy BLE advertisements physically only
//   fit 24 bytes of payload. So we have a dedicated, smaller struct for
//   beacons only. MeshPacketHeader is still used for all full packets sent
//   over GATT connections (no 31-byte limit there).
//
// VERSION 0x02 (security audit finding #3 / Fix 3): added a 7-byte pairwise
// MAC (see rezvan_crypto::beacon_mac) authenticating the beacon to any
// verifier that already knows the sender's X25519 identity key (learned via
// a prior KeyAnnouncement, packet type 0x05). There is no room for a real
// Ed25519 signature (64 bytes) in a 24-byte legacy BLE advertisement, so this
// intentionally trades non-repudiation for something that fits: see
// rezvan_crypto::beacon_mac module docs for exactly what guarantee this
// does and doesn't provide. A beacon from an unknown sender (no prior key
// exchange) cannot be verified and MUST be treated as discovery-only, never
// as input to a routing decision -- see routing.rs::process_ogm.
//
// WIRE LAYOUT (24 bytes total):
//   [0]      version       Protocol version. 0x02 (bumped from 0x01 -- old
//                          v0.1 beta builds are NOT wire-compatible with
//                          this version; that's a deliberate clean break,
//                          not a negotiated upgrade, since this is pre-1.0).
//   [1]      packet_type   0x01 = beacon. Never changes for this struct.
//   [2..10]  originator    Node ID (8 bytes = 64 bits = 18.4 quintillion IDs)
//   [10..14] sequence      u32 big-endian. Increments each tick. Peers use
//                          this to deduplicate beacons they already processed
//                          AND to reject replays (must be strictly greater
//                          than the last sequence seen from this originator).
//   [14]     battery       0-100 percent.
//                          Peers use this for routing: avoid relaying through
//                          low-battery nodes — they may die mid-transmission.
//   [15]     power_state   0=Emergency 1=Active 2=Balanced 3=PowerSaver
//                          4=Minimal 5=Hibernation 6=Dead
//                          Tells peers HOW HARD this node is working, not
//                          just how much fuel it has. A node at 40% in
//                          Hibernation is a worse relay than 30% in Active.
//   [16]     node_flags    Capabilities bitmask:
//                            bit 0 = is_charging   (charging at 5% = stable)
//                            bit 1 = has_wifi_direct (can bridge to WiFi)
//                            bit 2 = voice_capable   (can relay voice)
//                            bits 3-7 = RESERVED, always 0
//   [17..24] mac           7-byte pairwise MAC over bytes [0..17), keyed by
//                          ECDH(our X25519 privkey, sender's X25519 pubkey)
//                          -> HKDF. See rezvan_crypto::beacon_mac.
//
// WHAT WAS DROPPED vs the original v0.1 layout (ttl, peer_density, 5
// reserved bytes -- 7 bytes total) to make room for the MAC:
//   ttl           — was always 1 (beacons are never relayed) — pure
//                   constant, carried no information.
//   peer_density  — was produced but never consumed by any receiver on
//                   either the Rust or Kotlin side; safe to drop.
//   reserved (5)  — unused placeholder bytes.
// If a future version needs peer_density or similar back, it must go
// through the full MeshPacketHeader path instead, where there's room.
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdvBeaconExt {
    pub version: u8,
    pub packet_type: u8,
    pub originator: NodeId,
    pub sequence: u32,
    pub battery: u8,
    pub power_state: u8,
    pub node_flags: u8,
    pub mac: [u8; 7],
}

impl AdvBeaconExt {
    pub const SIZE: usize = 24;
    pub const VERSION: u8 = 0x02;
    pub const MAC_LEN: usize = 7;
    /// Length of the signed/MAC'd portion (everything before the MAC field).
    pub const SIGNED_LEN: usize = 17;

    pub const FLAG_CHARGING:    u8 = 0b0000_0001;
    pub const FLAG_WIFI_DIRECT: u8 = 0b0000_0010;
    pub const FLAG_VOICE:       u8 = 0b0000_0100;

    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIZE);
        buf.push(self.version);
        buf.push(self.packet_type);
        buf.extend_from_slice(&self.originator);
        buf.extend_from_slice(&self.sequence.to_be_bytes());
        buf.push(self.battery);
        buf.push(self.power_state);
        buf.push(self.node_flags);
        buf.extend_from_slice(&self.mac);
        buf
    }

    /// Serializes just the portion covered by the MAC (everything except the
    /// MAC field itself), for computing/verifying the tag.
    pub fn signed_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIGNED_LEN);
        buf.push(self.version);
        buf.push(self.packet_type);
        buf.extend_from_slice(&self.originator);
        buf.extend_from_slice(&self.sequence.to_be_bytes());
        buf.push(self.battery);
        buf.push(self.power_state);
        buf.push(self.node_flags);
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE { return None; }
        // Reject version mismatches here, at the source, rather than relying
        // on every caller to remember to check `version` themselves after
        // deserializing (this review's finding #4: engine.rs happened to
        // check it correctly, but nothing enforced that convention for the
        // next caller who might not know to).
        if data[0] != Self::VERSION { return None; }
        if data[1] != 0x01 { return None; }
        let mut originator = [0u8; 8];
        originator.copy_from_slice(&data[2..10]);
        let sequence = u32::from_be_bytes([data[10], data[11], data[12], data[13]]);
        let mut mac = [0u8; 7];
        mac.copy_from_slice(&data[17..24]);
        Some(Self {
            version:     data[0],
            packet_type: data[1],
            originator,
            sequence,
            battery:     data[14],
            power_state: data[15],
            node_flags:  data[16],
            mac,
        })
    }

    pub fn is_charging(&self)      -> bool { self.node_flags & Self::FLAG_CHARGING    != 0 }
    pub fn has_wifi_direct(&self)  -> bool { self.node_flags & Self::FLAG_WIFI_DIRECT != 0 }
    pub fn is_voice_capable(&self) -> bool { self.node_flags & Self::FLAG_VOICE       != 0 }
}

// COMPILE-TIME GUARD: if anyone changes AdvBeaconExt fields this will refuse
// to compile before the BLE advertisement can silently overflow.
const _: () = assert!(AdvBeaconExt::SIZE == 24);
const _: () = assert!(AdvBeaconExt::SIGNED_LEN + AdvBeaconExt::MAC_LEN == AdvBeaconExt::SIZE);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_header_size_constant_matches_serialization() {
        let hdr = MeshPacketHeader {
            version: 1, packet_type: 1, ttl: 1,
            originator: [0; 8], destination: [0; 8], sequence: 0, hop_count: 0,
            next_hop: [0; 8], payload_len: 0,
        };
        assert_eq!(hdr.serialize().len(), MeshPacketHeader::SIZE);
    }

    #[test]
    fn test_truncated_header_rejected() {
        let bytes = vec![0u8; MeshPacketHeader::SIZE - 1];
        assert!(MeshPacketHeader::deserialize(&bytes).is_none());
    }

    #[test]
    fn test_header_destination_roundtrip() {
        // Regression test for the version-0x03 relay support: `destination`
        // must survive serialize/deserialize distinctly from `originator`
        // and `next_hop` -- these three fields all matter for relay and must
        // never be conflated.
        let hdr = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type: 0x02,
            ttl: 5,
            originator: [1; 8],
            destination: [2; 8],
            sequence: 7,
            hop_count: 1,
            next_hop: [3; 8],
            payload_len: 0,
        };
        let ser = hdr.serialize();
        assert_eq!(ser.len(), MeshPacketHeader::SIZE);
        let deser = MeshPacketHeader::deserialize(&ser).unwrap();
        assert_eq!(hdr, deser);
        assert_ne!(deser.destination, deser.originator);
        assert_ne!(deser.destination, deser.next_hop);
    }

    #[test]
    fn test_broadcast_destination_is_all_zero() {
        assert_eq!(BROADCAST_DESTINATION, [0u8; 8]);
    }

    #[test]
    fn test_header_fuzz_no_panic() {
        for len in 0..30 {
            let bytes = vec![0xAAu8; len];
            let _ = MeshPacketHeader::deserialize(&bytes);
        }
    }

    #[test]
    fn test_ogm_roundtrip() {
        let ogm = OGMPayload {
            timestamp: 123456789,
            link_quality: 200,
            path_metric: 500,
            neighbor_count: 1,
            neighbors: [NeighborInfo::default(); 9],
        };
        let ser = ogm.serialize();
        assert_eq!(ser.len(), OGMPayload::SIZE);
        let deser = OGMPayload::deserialize(&ser).unwrap();
        assert_eq!(ogm, deser);
    }

    #[test]
    fn test_ogm_fuzz_no_panic() {
        for len in 0..60 {
            let bytes = vec![0xAAu8; len];
            let _ = OGMPayload::deserialize(&bytes);
        }
    }

    #[test]
    fn test_message_roundtrip() {
        let msg = DecryptedMessage {
            conversation_id: [1; 16],
            sender_id: [2; 8],
            timestamp: 99999,
            message_type: 0,
            protocol_message_id: Some([9; 16]),
            content: b"hello mesh".to_vec(),
        };
        let ser = msg.serialize();
        let deser = DecryptedMessage::deserialize(&ser).unwrap();
        assert_eq!(msg, deser);
    }

    #[test]
    fn test_gate1_direct_envelope_roundtrip() {
        let envelope = DirectMessageEnvelopeV1 {
            message_kind: 0,
            message_id: [0xA5; 16],
            created_at_ms: 1_700_000_000_000,
            body: b"hello mesh".to_vec(),
        };
        let wire = envelope.serialize().unwrap();
        assert_eq!(DirectMessageEnvelopeV1::deserialize(&wire), Some(envelope));
    }

    #[test]
    fn test_gate1_direct_envelope_rejects_bad_length_and_zero_id() {
        let bad_id = DirectMessageEnvelopeV1 {
            message_kind: 0,
            message_id: [0; 16],
            created_at_ms: 1,
            body: b"x".to_vec(),
        };
        assert!(bad_id.serialize().is_none());
        let mut wire = DirectMessageEnvelopeV1 {
            message_kind: 0,
            message_id: [1; 16],
            created_at_ms: 1,
            body: b"x".to_vec(),
        }.serialize().unwrap();
        wire[31] = 2;
        assert!(DirectMessageEnvelopeV1::deserialize(&wire).is_none());
    }

    #[test]
    fn test_gate1_ack_envelope_roundtrip() {
        let ack = MessageAckEnvelopeV1 {
            message_id: [0xA5; 16],
            original_sender: [1; 8],
            original_recipient: [2; 8],
            created_at_ms: 42,
        };
        let wire = ack.serialize();
        assert_eq!(wire.len(), MessageAckEnvelopeV1::SIZE);
        assert_eq!(MessageAckEnvelopeV1::deserialize(&wire), Some(ack));
    }

    #[test]
    fn test_beacon_roundtrip_v2_layout() {
        let beacon = AdvBeaconExt {
            version: AdvBeaconExt::VERSION,
            packet_type: 0x01,
            originator: [7; 8],
            sequence: 42,
            battery: 88,
            power_state: 1,
            node_flags: AdvBeaconExt::FLAG_CHARGING | AdvBeaconExt::FLAG_WIFI_DIRECT,
            mac: [0xAB; 7],
        };
        let ser = beacon.serialize();
        assert_eq!(ser.len(), AdvBeaconExt::SIZE);
        let deser = AdvBeaconExt::deserialize(&ser).unwrap();
        assert_eq!(beacon, deser);
        assert!(deser.is_charging());
        assert!(deser.has_wifi_direct());
        assert!(!deser.is_voice_capable());
    }

    #[test]
    fn test_beacon_signed_bytes_excludes_mac() {
        let beacon = AdvBeaconExt {
            version: AdvBeaconExt::VERSION,
            packet_type: 0x01,
            originator: [1; 8],
            sequence: 1,
            battery: 50,
            power_state: 0,
            node_flags: 0,
            mac: [0xFF; 7],
        };
        let signed = beacon.signed_bytes();
        assert_eq!(signed.len(), AdvBeaconExt::SIGNED_LEN);
        // Changing only the MAC field must not change signed_bytes().
        let mut beacon2 = beacon.clone();
        beacon2.mac = [0x00; 7];
        assert_eq!(signed, beacon2.signed_bytes());
    }

    #[test]
    fn test_beacon_truncated_rejected() {
        let bytes = vec![0u8; AdvBeaconExt::SIZE - 1];
        assert!(AdvBeaconExt::deserialize(&bytes).is_none());
    }

    #[test]
    fn test_beacon_version_mismatch_rejected_by_deserialize_itself() {
        // Regression test for finding #4: deserialize() must reject a
        // version mismatch on its own, rather than relying on every caller
        // to separately check `version` after a successful parse.
        let beacon = AdvBeaconExt {
            version: AdvBeaconExt::VERSION,
            packet_type: 0x01,
            originator: [1; 8],
            sequence: 1,
            battery: 50,
            power_state: 0,
            node_flags: 0,
            mac: [0u8; 7],
        };
        let mut bytes = beacon.serialize();
        bytes[0] = AdvBeaconExt::VERSION.wrapping_sub(1); // wrong version byte
        assert!(
            AdvBeaconExt::deserialize(&bytes).is_none(),
            "deserialize must reject a version mismatch itself, not defer it to the caller"
        );
    }

    #[test]
    fn test_beacon_fuzz_no_panic() {
        for len in 0..30 {
            let bytes = vec![0xAAu8; len];
            let _ = AdvBeaconExt::deserialize(&bytes);
        }
    }
    }