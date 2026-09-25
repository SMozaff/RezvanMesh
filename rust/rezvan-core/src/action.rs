use rezvan_common::{DecryptedMessage, MessageId, NodeId};

#[derive(Debug, Clone)]
pub enum Action {
    /// Send a BLE advertisement carrying an `AdvBeaconExt` payload.
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

/// Maximum actions in one frame -- the count is a single byte on the wire and
/// is read back with `and 0xFF` on the Kotlin side, so a longer list would
/// wrap and desynchronise the whole frame.
pub const MAX_ACTIONS_PER_FRAME: usize = u8::MAX as usize;

/// Maximum payload for a single action -- the length prefix is a `u16`.
///
/// This is a real ceiling, not a theoretical one. `NotifyUi` payloads embed a
/// `DecryptedMessage`, whose own content length is a `u32`; a large text
/// message or an inbound file chunk can therefore exceed 65535 bytes and
/// overflow the `u16` prefix. When that happened the truncated length made
/// Kotlin's `ActionDispatcher` mis-slice the frame, so every action *after*
/// the oversized one was decoded from the wrong offset -- silently corrupting
/// unrelated packets in the same frame.
pub const MAX_ACTION_PAYLOAD: usize = u16::MAX as usize;

/// Cap for the free-form strings inside a `DiagLog` action.
///
/// Diagnostics are not worth failing a frame over, so oversized strings are
/// truncated rather than dropped. 1 KiB is far more than any diagnostic the
/// engine produces, and it also guarantees the two internal `u16` length
/// fields below can never overflow.
const MAX_DIAG_FIELD: usize = 1024;

/// Serialize a batch of actions into the wire format Kotlin's
/// `ActionDispatcher` parses.
///
/// Two invariants this function is responsible for upholding:
///
/// * the action count fits in one byte, and
/// * every payload length fits in a `u16` and matches the bytes actually
///   written.
///
/// If an action cannot be represented, it is **skipped** rather than emitted
/// with a truncated header. Skipping keeps the frame parseable: dropping the
/// whole batch would mean one oversized diagnostic costing us a real packet
/// that happened to be in the same frame, which is strictly worse.
pub fn serialize_actions(actions: &[Action]) -> Vec<u8> {
    if actions.is_empty() {
        return vec![0u8];
    }

    let mut body = Vec::new();
    let mut kept = 0usize;

    for action in actions {
        if kept >= MAX_ACTIONS_PER_FRAME {
            // Anything past the cap has nowhere to go in this frame; the next
            // batch will carry it.
            break;
        }
        if serialize_one(&mut body, action) {
            kept += 1;
        }
    }

    if kept == 0 {
        // Nothing survived -- emit the canonical empty frame so the caller
        // still gets a well-formed (if useless) result rather than a lone
        // count byte the Kotlin side would reject.
        return vec![0u8];
    }

    let mut buf = Vec::with_capacity(1 + body.len());
    buf.push(kept as u8);
    buf.extend_from_slice(&body);
    buf
}

/// Append one action. Returns `false` if it was skipped, either because its
/// payload is unrepresentable in a `u16` or because the caller's budget was
/// exhausted.
fn serialize_one(buf: &mut Vec<u8>, action: &Action) -> bool {
    match action {
        Action::SendBleAdvertisement { data } => {
            let payload = prepare_ble_adv_payload(data);
            write_action(buf, 1, &payload)
        }
        Action::SendWifiPacket { ip, port, data } => {
            if data.len() > MAX_ACTION_PAYLOAD - 6 {
                return false;
            }
            let mut payload = Vec::with_capacity(6 + data.len());
            payload.extend_from_slice(&ip.to_be_bytes());
            payload.extend_from_slice(&port.to_be_bytes());
            payload.extend_from_slice(data);
            write_action(buf, 2, &payload)
        }
        Action::SendBlePacket { target, data } => {
            if data.len() > MAX_ACTION_PAYLOAD - 8 {
                return false;
            }
            let mut payload = Vec::with_capacity(8 + data.len());
            payload.extend_from_slice(target);
            payload.extend_from_slice(data);
            write_action(buf, 3, &payload)
        }
        Action::UpdateScanInterval { interval_ms, window_ms } => {
            let mut payload = Vec::with_capacity(8);
            payload.extend_from_slice(&interval_ms.to_be_bytes());
            payload.extend_from_slice(&window_ms.to_be_bytes());
            write_action(buf, 4, &payload)
        }
        Action::NotifyUi { decrypted_message } => {
            let payload = decrypted_message.serialize();
            write_action(buf, 5, &payload)
        }
        Action::DiagLog { tag, level, message } => {
            // Truncate rather than drop: a diagnostic is still useful when
            // clipped, and clipping cannot overflow the inner length fields.
            let tag_bytes: Vec<u8> = tag.as_bytes().iter().copied().take(MAX_DIAG_FIELD).collect();
            let msg_bytes: Vec<u8> = message
                .as_bytes()
                .iter()
                .copied()
                .take(MAX_DIAG_FIELD)
                .collect();
            let mut payload = Vec::with_capacity(1 + 2 + tag_bytes.len() + 2 + msg_bytes.len());
            payload.push(*level);
            payload.extend_from_slice(&(tag_bytes.len() as u16).to_be_bytes());
            payload.extend_from_slice(&tag_bytes);
            payload.extend_from_slice(&(msg_bytes.len() as u16).to_be_bytes());
            payload.extend_from_slice(&msg_bytes);
            write_action(buf, 6, &payload)
        }
        Action::MessageAcknowledged { message_id, ack_sender } => {
            let mut payload = Vec::with_capacity(24);
            payload.extend_from_slice(message_id);
            payload.extend_from_slice(ack_sender);
            write_action(buf, 7, &payload)
        }
    }
}

/// Pad/truncate an advertisement payload to the legacy BLE advertising budget.
///
/// The platform budget is 31 bytes of *advertising data* total, but that is not
/// what this field is: three bytes go to the flags AD structure and two to the
/// manufacturer-ID envelope, leaving 24 bytes of actual manufacturer data --
/// which is exactly `AdvBeaconExt::SIZE`.
///
/// This used to pad to 31, so every advertisement was serialised with 7 bytes
/// of zeros, crossed the JNI boundary, and was then truncated straight back to
/// 24 on the Android side (`RadioControllerImpl.startLegacyAdvertising` logs
/// `dropped=7` on every start). Nothing was broken, but it wasted an
/// allocation and a copy per advertise cycle on a battery-powered device, and
/// the mismatch between the two numbers is a trap for the next reader.
fn prepare_ble_adv_payload(data: &[u8]) -> Vec<u8> {
    use rezvan_common::AdvBeaconExt;
    let mut fixed = vec![0u8; AdvBeaconExt::SIZE];
    let len = data.len().min(AdvBeaconExt::SIZE);
    fixed[..len].copy_from_slice(&data[..len]);
    fixed
}

/// Write a `u16` length prefix followed by the payload.
///
/// Returns `false` without writing anything when the payload is too large, so
/// the caller can skip the action and keep the frame parseable.
fn write_action(buf: &mut Vec<u8>, action_type: u8, payload: &[u8]) -> bool {
    if payload.len() > MAX_ACTION_PAYLOAD {
        return false;
    }
    buf.push(action_type);
    buf.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    buf.extend_from_slice(payload);
    true
}

#[allow(dead_code)]
fn write_payload(buf: &mut Vec<u8>, payload: &[u8]) -> bool {
    if payload.len() > MAX_ACTION_PAYLOAD {
        return false;
    }
    buf.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    buf.extend_from_slice(payload);
    true
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
        // The payload must be exactly AdvBeaconExt::SIZE so nothing is padded
        // on one side and truncated on the other.
        let size = rezvan_common::AdvBeaconExt::SIZE;
        let actions = vec![Action::SendBleAdvertisement {
            data: vec![0xAB; size],
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[0], 1);
        assert_eq!(serialized[1], 0x01);
        assert_eq!(u16::from_be_bytes([serialized[2], serialized[3]]) as usize, size);
        assert_eq!(serialized.len(), 1 + 1 + 2 + size);
    }

    /// An over-long advertisement payload is truncated to the exact wire size,
    /// never padded beyond it. Regression test for the old 31-byte padding,
    /// which shipped 7 zero bytes per advertise cycle that Android immediately
    /// discarded.
    #[test]
    fn advertisement_payload_is_exactly_the_wire_size() {
        for input in [0usize, 1, 23, 24, 25, 31, 64] {
            let actions = vec![Action::SendBleAdvertisement { data: vec![0x5A; input] }];
            let serialized = serialize_actions(&actions);
            let declared = u16::from_be_bytes([serialized[2], serialized[3]]) as usize;
            let expected = input.min(rezvan_common::AdvBeaconExt::SIZE);
            assert_eq!(declared, expected, "input {input}");
            assert_eq!(serialized.len(), 1 + 1 + 2 + expected, "input {input}");
        }
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

    // --- frame-integrity tests ------------------------------------------------
    //
    // The Kotlin side (`ActionDispatcher.dispatch`, and the hand-rolled parser
    // in `RezvanRadioService.onPacketReceived`) walks the frame with a simple
    // `offset += 3; offset += payloadLen` loop. If a serialized payload's
    // declared length does not match the bytes actually written, that loop
    // lands on the wrong offset and every action after the bad one is either
    // mis-decoded or silently dropped.
    //
    // `parse_frame` below mirrors that loop exactly, so a serialization bug
    // surfaces here as "did not consume the whole frame" rather than as a
    // message that mysteriously never arrives on a real device.

    /// Returns the parsed actions plus how many bytes the walk consumed.
    fn parse_frame(frame: &[u8]) -> (Vec<(u8, Vec<u8>)>, usize) {
        assert!(frame.len() >= 4, "frame too short: {} bytes", frame.len());
        let count = frame[0] as usize;
        let mut offset = 1usize;
        let mut out = Vec::new();
        for _ in 0..count {
            assert!(offset + 3 <= frame.len(), "truncated action header at {offset}");
            let ty = frame[offset];
            let len = ((frame[offset + 1] as usize) << 8) | frame[offset + 2] as usize;
            offset += 3;
            assert!(
                offset + len <= frame.len(),
                "truncated action payload at {offset}, declared {len} bytes"
            );
            out.push((ty, frame[offset..offset + len].to_vec()));
            offset += len;
        }
        (out, offset)
    }

    /// Regression test: the `DiagLog` payload declared a `message` length but
    /// never wrote the message bytes, so every diagnostic action left the
    /// frame walk short by exactly the message length. Since `DiagLog` is what
    /// the engine emits for every packet rejection, a batch like
    /// `[DiagLog, NotifyUi]` silently dropped the message.
    #[test]
    fn diaglog_payload_length_matches_the_bytes_written() {
        let message = "KeyAnnouncement REJECTED: embedded key does not hash to claimed NodeId";
        let actions = vec![Action::DiagLog {
            tag: "RUST".into(),
            level: 3,
            message: message.into(),
        }];
        let frame = serialize_actions(&actions);
        let (parsed, consumed) = parse_frame(&frame);

        assert_eq!(consumed, frame.len(), "frame walk must consume the whole frame");
        assert_eq!(parsed.len(), 1);
        let (ty, payload) = &parsed[0];
        assert_eq!(*ty, 0x06);
        // [level:1][tag_len:2][tag][msg_len:2][msg]
        assert_eq!(payload[0], 3);
        let tag_len = ((payload[1] as usize) << 8) | payload[2] as usize;
        assert_eq!(&payload[3..3 + tag_len], &b"RUST"[..]);
        let msg_len_at = 3 + tag_len;
        let msg_len = ((payload[msg_len_at] as usize) << 8) | payload[msg_len_at + 1] as usize;
        assert_eq!(msg_len, message.len());
        assert_eq!(
            &payload[msg_len_at + 2..msg_len_at + 2 + msg_len],
            message.as_bytes(),
            "the message bytes must actually be present, not just counted"
        );
        assert_eq!(msg_len_at + 2 + msg_len, payload.len());
    }

    /// The bug above was only *visible* when a real action followed the
    /// diagnostic. Assert that ordering explicitly, since this is the shape
    /// that silently lost messages.
    #[test]
    fn a_diaglog_before_a_real_action_does_not_desync_the_frame() {
        let msg = DecryptedMessage {
            conversation_id: [0x01; 16],
            sender_id: [0x02; 8],
            timestamp: 99,
            message_type: 0,
            protocol_message_id: Some([0xAB; 16]),
            content: b"important".to_vec(),
        };
        let actions = vec![
            Action::DiagLog { tag: "RUST".into(), level: 1, message: "rejected something".into() },
            Action::NotifyUi { decrypted_message: msg.clone() },
        ];
        let frame = serialize_actions(&actions);
        let (parsed, consumed) = parse_frame(&frame);

        assert_eq!(consumed, frame.len());
        assert_eq!(parsed.len(), 2, "both actions must survive");
        assert_eq!(parsed[0].0, 0x06);
        assert_eq!(parsed[1].0, 0x05);
        let decoded = DecryptedMessage::deserialize(&parsed[1].1).expect("second action decodes");
        assert_eq!(decoded.content.as_slice(), &b"important"[..]);
    }

    /// A payload that cannot fit the `u16` length prefix must be skipped
    /// without corrupting the frame, so the other actions in the batch still
    /// reach Kotlin.
    #[test]
    fn an_unrepresentable_action_is_skipped_not_truncated() {
        // 8 bytes of NodeId + oversized data would overflow the u16 prefix.
        let actions = vec![
            Action::SendBlePacket {
                target: [0x11; 8],
                data: vec![0xAA; MAX_ACTION_PAYLOAD],
            },
            Action::MessageAcknowledged { message_id: [0x01; 16], ack_sender: [0x22; 8] },
        ];
        let frame = serialize_actions(&actions);
        let (parsed, consumed) = parse_frame(&frame);

        assert_eq!(consumed, frame.len());
        assert_eq!(
            parsed.len(),
            1,
            "only the representable action should be emitted"
        );
        assert_eq!(parsed[0].0, 0x07, "the ACK must still be delivered");
        assert_eq!(parsed[0].1.len(), 24);
    }

    /// An action list longer than the one-byte count field is capped rather
    /// than wrapped -- wrapping would make Kotlin read the wrong action count
    /// and mis-slice the entire frame.
    #[test]
    fn an_over_long_action_list_is_capped_at_the_count_field_limit() {
        let many = vec![
            Action::UpdateScanInterval { interval_ms: 1, window_ms: 1 };
            MAX_ACTIONS_PER_FRAME + 10
        ];
        let frame = serialize_actions(&many);
        assert_eq!(frame[0] as usize, MAX_ACTIONS_PER_FRAME);
        let (parsed, consumed) = parse_frame(&frame);
        assert_eq!(consumed, frame.len());
        assert_eq!(parsed.len(), MAX_ACTIONS_PER_FRAME);
    }

    /// An oversized diagnostic is truncated, not dropped: a clipped log line is
    /// still useful, and clipping cannot overflow the inner length fields.
    #[test]
    fn oversized_diaglog_strings_are_truncated_not_dropped() {
        let huge = "x".repeat(MAX_DIAG_FIELD * 4);
        let actions = vec![Action::DiagLog { tag: "T".into(), level: 2, message: huge }];
        let frame = serialize_actions(&actions);
        let (parsed, consumed) = parse_frame(&frame);

        assert_eq!(consumed, frame.len());
        assert_eq!(parsed.len(), 1, "a diagnostic must never be dropped entirely");
        let payload = &parsed[0].1;
        let tag_len = ((payload[1] as usize) << 8) | payload[2] as usize;
        let msg_len_at = 3 + tag_len;
        let msg_len = ((payload[msg_len_at] as usize) << 8) | payload[msg_len_at + 1] as usize;
        assert_eq!(msg_len, MAX_DIAG_FIELD, "message clipped to the cap");
        assert_eq!(msg_len_at + 2 + msg_len, payload.len());
    }

    /// A batch in which *nothing* is representable still yields the canonical
    /// empty frame rather than a count byte with no body.
    #[test]
    fn a_fully_unrepresentable_batch_yields_the_empty_frame() {
        let actions = vec![Action::SendBlePacket {
            target: [0x11; 8],
            data: vec![0xAA; MAX_ACTION_PAYLOAD + 1],
        }];
        assert_eq!(serialize_actions(&actions), vec![0u8]);
    }
}