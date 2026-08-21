use rezvan_common::{DecryptedMessage, MessageId, NodeId};

#[derive(Debug, Clone)]
pub enum Action {
    /// Send a 31‑byte BLE advertisement.
    SendBleAdvertisement { data: Vec<u8> },

    /// Send a raw packet over Wi‑Fi Direct.
    SendWifiPacket { ip: u32, port: u16, data: Vec<u8> },

    /// Send a raw BLE packet to a specific peer, identified by their mesh
    /// NodeId (NOT a BLE MAC address -- Rust has no business knowing
    /// transport-layer addressing, and a NodeId is stable across
    /// reconnects/MAC rotation while a MAC address is not). Kotlin resolves
    /// NodeId -> current BLE MAC (or WiFi-Direct IP) at dispatch time, and
    /// queues the packet if no live connection to that peer exists yet.
    ///
    /// `target == [0u8; 8]` (all-zero) is the broadcast sentinel: send to
    /// every currently-connected peer, used for true broadcasts (emergency
    /// alerts, KeyAnnouncement) rather than 1:1 direct messages.
    SendBlePacket { target: NodeId, data: Vec<u8> },

    /// Change BLE scan duty‑cycle parameters.
    UpdateScanInterval { interval_ms: u32, window_ms: u32 },

    /// Notify the Kotlin UI of a newly decrypted message.
    NotifyUi { decrypted_message: DecryptedMessage },

    /// Diagnostic log entry – surfaced to Kotlin for in‑app display.
    DiagLog { tag: String, level: u8, message: String },

    /// A signed, encrypted Gate 1 acknowledgement matched an outbound message.
    /// Kotlin must still match this tuple against a persisted outbound record.
    MessageAcknowledged { message_id: MessageId, ack_sender: NodeId },
}

/// Broadcast sentinel for `Action::SendBlePacket::target` -- send to every
/// currently-connected peer rather than resolving a specific NodeId to a MAC.
pub const BROADCAST_TARGET: NodeId = [0u8; 8];

pub fn serialize_actions(actions: &[Action]) -> Vec<u8> {
    if actions.is_empty() {
        return vec![0u8];
    }

    let mut buf = Vec::new();
    buf.push(actions.len() as u8);

    for action in actions {
        serialize_one(&mut buf, action);
    }
    buf
}

fn serialize_one(buf: &mut Vec<u8>, action: &Action) {
    match action {
        Action::SendBleAdvertisement { data } => {
            buf.push(0x01);
            let payload = prepare_ble_adv_payload(data);
            write_payload(buf, &payload);
        }
        Action::SendWifiPacket { ip, port, data } => {
            buf.push(0x02);
            let mut payload = Vec::with_capacity(6 + data.len());
            payload.extend_from_slice(&ip.to_be_bytes());
            payload.extend_from_slice(&port.to_be_bytes());
            payload.extend_from_slice(data);
            write_payload(buf, &payload);
        }
        Action::SendBlePacket { target, data } => {
            buf.push(0x03);
            let mut payload = Vec::with_capacity(8 + data.len());
            payload.extend_from_slice(target);
            payload.extend_from_slice(data);
            write_payload(buf, &payload);
        }
        Action::UpdateScanInterval { interval_ms, window_ms } => {
            buf.push(0x04);
            let mut payload = Vec::with_capacity(8);
            payload.extend_from_slice(&interval_ms.to_be_bytes());
            payload.extend_from_slice(&window_ms.to_be_bytes());
            write_payload(buf, &payload);
        }
        Action::NotifyUi { decrypted_message } => {
            buf.push(0x05);
            let payload = decrypted_message.serialize();
            write_payload(buf, &payload);
        }
        Action::DiagLog { tag, level, message } => {
            buf.push(0x06);
            let tag_bytes = tag.as_bytes();
            let msg_bytes = message.as_bytes();
            let mut payload = Vec::with_capacity(1 + 2 + tag_bytes.len() + 2 + msg_bytes.len());
            payload.push(*level);
            payload.extend_from_slice(&(tag_bytes.len() as u16).to_be_bytes());
            payload.extend_from_slice(tag_bytes);
            payload.extend_from_slice(&(msg_bytes.len() as u16).to_be_bytes());
            write_payload(buf, &payload);
        }
        Action::MessageAcknowledged { message_id, ack_sender } => {
            buf.push(0x07);
            let mut payload = Vec::with_capacity(24);
            payload.extend_from_slice(message_id);
            payload.extend_from_slice(ack_sender);
            write_payload(buf, &payload);
        }
    }
}

fn prepare_ble_adv_payload(data: &[u8]) -> Vec<u8> {
    let mut fixed = vec![0u8; 31];
    let len = data.len().min(31);
    fixed[..len].copy_from_slice(&data[..len]);
    fixed
}

fn write_payload(buf: &mut Vec<u8>, payload: &[u8]) {
    let len = (payload.len() as u16).to_be_bytes();
    buf.extend_from_slice(&len);
    buf.extend_from_slice(payload);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_serialize_empty() {
        let actions: Vec<Action> = vec![];
        let result = serialize_actions(&actions);
        assert_eq!(result, vec![0u8]);
    }

    #[test]
    fn test_serialize_ble_advertisement() {
        let actions = vec![Action::SendBleAdvertisement {
            data: vec![0xAB; 31],
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[0], 1);
        assert_eq!(serialized[1], 0x01);
        assert_eq!(u16::from_be_bytes([serialized[2], serialized[3]]), 31);
        assert_eq!(serialized.len(), 1 + 1 + 2 + 31);
    }

    #[test]
    fn test_serialize_wifi_packet() {
        let actions = vec![Action::SendWifiPacket {
            ip: 0xC0A80001,
            port: 4237,
            data: b"hello".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[0], 1);
        assert_eq!(serialized[1], 0x02);
        let payload_len = u16::from_be_bytes([serialized[2], serialized[3]]) as usize;
        assert_eq!(payload_len, 6 + 5);
    }

    #[test]
    fn test_serialize_ble_packet_targeted() {
        let target: NodeId = [0xAA; 8];
        let actions = vec![Action::SendBlePacket {
            target,
            data: b"secret".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[1], 0x03);
        let payload_len = u16::from_be_bytes([serialized[2], serialized[3]]) as usize;
        assert_eq!(payload_len, 8 + 6);
        let payload = &serialized[4..];
        assert_eq!(&payload[0..8], &target);
        assert_eq!(&payload[8..], b"secret");
    }

    #[test]
    fn test_serialize_ble_packet_broadcast_sentinel() {
        let actions = vec![Action::SendBlePacket {
            target: BROADCAST_TARGET,
            data: b"emergency".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        let payload = &serialized[4..];
        assert_eq!(&payload[0..8], &[0u8; 8]);
    }

    #[test]
    fn test_serialize_update_scan() {
        let actions = vec![Action::UpdateScanInterval {
            interval_ms: 5000,
            window_ms: 250,
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[1], 0x04);
        let payload_len = u16::from_be_bytes([serialized[2], serialized[3]]);
        assert_eq!(payload_len, 8);
    }

    #[test]
    fn test_serialize_notify_ui() {
        let msg = DecryptedMessage {
            conversation_id: [0x01; 16],
            sender_id: [0x02; 8],
            timestamp: 12345,
            message_type: 0,
            protocol_message_id: None,
            content: vec![0xAA; 10],
        };
        let actions = vec![Action::NotifyUi {
            decrypted_message: msg.clone(),
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[1], 0x05);
        let payload = &serialized[4..];
        let decoded = DecryptedMessage::deserialize(payload).unwrap();
        assert_eq!(decoded.timestamp, msg.timestamp);
    }
}