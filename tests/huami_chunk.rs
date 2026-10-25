use band7_terminal::huami_chunk::*;
// Synthetic header vectors derived from the pinned Java framing, not BLE captures.
const FIRST: &[u8] = &[3, 1, 0, 7, 0, 4, 0, 0, 0, 0xa0, 0, 10, 20];
const LAST: &[u8] = &[3, 6, 0, 7, 1, 30, 40];

#[test]
fn reconstructs_extended_headers_and_ack_metadata() {
    let mut rx = Receiver::new(1);
    assert!(rx.receive(1, 0, FIRST).unwrap().message.is_none());
    let end = rx.receive(1, 1, LAST).unwrap();
    assert!(end.ack_requested);
    assert_eq!(end.handle, 7);
    assert_eq!(end.sequence, 1);
    assert_eq!(
        end.message.unwrap(),
        Message {
            endpoint: 0xa0,
            payload: vec![10, 20, 30, 40]
        }
    );
    assert_eq!(rx.receive(1, 2, LAST), Err(RxError::Sequence));
}
#[test]
fn bounded_header_validation_and_no_encrypted_fallback() {
    for len in 0..FIRST.len() {
        let mut rx = Receiver::new(1);
        if len != 12 {
            assert!(rx.receive(1, 0, &FIRST[..len]).is_err());
        }
    }
    for (offset, value, error) in [
        (0, 4, RxError::Header),
        (1, 9, RxError::EncryptedUnsupported),
        (1, 0x11, RxError::Header),
        (2, 1, RxError::Header),
        (4, 1, RxError::Sequence),
        (5, 0, RxError::Length),
        (8, 1, RxError::Length),
    ] {
        let mut bytes = FIRST.to_vec();
        bytes[offset] = value;
        assert_eq!(Receiver::new(1).receive(1, 0, &bytes), Err(error));
    }
    assert_eq!(
        Receiver::new(1).receive(1, 0, &vec![0; 513]),
        Err(RxError::Header)
    );
}
#[test]
fn sequencing_errors_reset_and_do_not_produce_partial_output() {
    for bytes in [
        FIRST.to_vec(),
        vec![3, 6, 0, 8, 1, 30, 40],
        vec![3, 6, 0, 7, 2, 30, 40],
        vec![3, 6, 0, 7, 1, 30],
        vec![3, 6, 0, 7, 1, 30, 40, 50],
        vec![3, 0, 0, 7, 1, 30, 40],
    ] {
        let mut rx = Receiver::new(1);
        rx.receive(1, 0, FIRST).unwrap();
        assert!(rx.receive(1, 1, &bytes).is_err());
        assert_eq!(rx.receive(1, 2, LAST), Err(RxError::Sequence));
        assert!(rx.receive(1, 3, FIRST).is_ok());
    }
}
#[test]
fn deadline_is_absolute_and_reconnect_rejects_stale_callbacks() {
    let mut rx = Receiver::new(10);
    rx.receive(10, 1, FIRST).unwrap();
    assert_eq!(rx.expire(5001), Err(RxError::Timeout));
    assert_eq!(rx.receive(10, 5002, LAST), Err(RxError::Sequence));
    let mut next = Receiver::new(11);
    next.receive(11, 0, FIRST).unwrap();
    assert_eq!(next.receive(10, 1, LAST), Err(RxError::Session));
    assert!(next.receive(11, 2, LAST).unwrap().message.is_some());
    next.receive(11, 3, FIRST).unwrap();
    assert_eq!(next.receive(11, 2, LAST), Err(RxError::Clock));
    assert_eq!(next.receive(11, 4, LAST), Err(RxError::Sequence));
}
#[test]
fn one_frame_message_and_short_final_before_deadline() {
    let frame = [3, 7, 0, 255, 0, 1, 0, 0, 0, 0x34, 0x12, 99];
    let end = Receiver::new(1).receive(1, 0, &frame).unwrap();
    assert_eq!(end.message.unwrap().endpoint, 0x1234);
    let mut rx = Receiver::new(1);
    rx.receive(1, 0, FIRST).unwrap();
    assert!(rx
        .receive(1, TIMEOUT_MS - 1, LAST)
        .unwrap()
        .message
        .is_some());
}

#[test]
fn accepts_policy_maximum_and_full_sequence_space_without_wrap() {
    let mut rx = Receiver::new(1);
    for sequence in 0..=255u16 {
        let mut frame = vec![
            3,
            if sequence == 0 {
                1
            } else if sequence == 255 {
                6
            } else {
                0
            },
            0,
            4,
            sequence as u8,
        ];
        if sequence == 0 {
            frame.extend_from_slice(&[0, 16, 0, 0, 0xa0, 0]);
        }
        frame.extend_from_slice(&[42; 16]);
        let received = rx.receive(1, u64::from(sequence), &frame).unwrap();
        if sequence == 255 {
            assert_eq!(received.message.unwrap().payload, vec![42; 4096]);
        } else {
            assert!(received.message.is_none());
        }
    }
}

#[test]
fn arbitrary_bytes_never_panic_and_cannot_poison_next_message() {
    let complete = [3, 7, 0, 1, 0, 1, 0, 0, 0, 0, 0, 42];
    for length in 0..=513 {
        let mut rx = Receiver::new(1);
        let bytes: Vec<_> = (0..length)
            .map(|i| ((i * 71 + length * 13) % 256) as u8)
            .collect();
        let _ = rx.receive(1, 0, &bytes);
        let mut fresh = Receiver::new(2);
        assert!(fresh.receive(2, 0, &complete).unwrap().message.is_some());
    }
}
