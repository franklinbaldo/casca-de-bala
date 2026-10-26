use band7_terminal::huami_auth::{decode_reply, ParseError, Reply, AUTH_ENDPOINT};

#[test]
fn challenge_fields_are_borrowed_wire_bytes_not_validated_keys() {
    let mut p = [0u8; 67];
    p[..3].copy_from_slice(&[0x10, 0x04, 0x01]);
    p[3..19].fill(0x11);
    // Deliberately invalid all-zero curve point: the envelope parser must not
    // claim cryptographic validation, derive keys or authenticate the session.
    match decode_reply(AUTH_ENDPOINT, &p).unwrap() {
        Reply::Challenge(c) => {
            assert_eq!(c.nonce(), &[0x11; 16]);
            assert_eq!(c.unvalidated_public_key(), &[0; 48]);
            assert_eq!(c.nonce().as_ptr(), p[3..].as_ptr());
        }
        _ => panic!("wrong reply class"),
    }
}

#[test]
fn peer_status_is_not_a_local_authentication_proof() {
    assert!(matches!(
        decode_reply(AUTH_ENDPOINT, &[0x10, 5, 1]),
        Ok(Reply::PeerReportedSuccess)
    ));
    assert!(matches!(
        decode_reply(AUTH_ENDPOINT, &[0x10, 5, 0x25]),
        Ok(Reply::PeerReportedBadKey)
    ));
}

#[test]
fn rejects_every_truncation_trailing_bytes_wrong_endpoint_and_unknown_codes() {
    let mut p = [0; 68];
    p[..3].copy_from_slice(&[0x10, 4, 1]);
    for n in 0..67 {
        assert!(matches!(
            decode_reply(AUTH_ENDPOINT, &p[..n]),
            Err(ParseError::Length)
        ));
    }
    assert!(matches!(
        decode_reply(AUTH_ENDPOINT, &p),
        Err(ParseError::Length)
    ));
    assert!(matches!(
        decode_reply(0, &p[..67]),
        Err(ParseError::Endpoint)
    ));
    assert!(matches!(
        decode_reply(AUTH_ENDPOINT, &[0, 5, 1]),
        Err(ParseError::Header)
    ));
    for payload in [[0x10, 6, 1], [0x10, 5, 2], [0x10, 4, 0x25]] {
        assert!(matches!(
            decode_reply(AUTH_ENDPOINT, &payload),
            Err(ParseError::Unsupported)
        ));
    }
    assert!(matches!(
        decode_reply(AUTH_ENDPOINT, &[0x10, 5, 1, 0]),
        Err(ParseError::Length)
    ));
}

#[test]
fn synthetic_plaintext_auth_fragments_use_existing_receiver_without_authenticating() {
    use band7_terminal::huami_chunk::{Receiver, RxError};
    // Synthetic extended Huami2021 layout from the existing framing tests, not
    // a capture or proof that the real unit selected this header/crypto mode.
    let mut payload = [0u8; 67];
    payload[..3].copy_from_slice(&[0x10, 4, 1]);
    let mut first = vec![3, 1, 0, 7, 0, 67, 0, 0, 0, 0x82, 0];
    first.extend_from_slice(&payload[..20]);
    let mut last = vec![3, 6, 0, 7, 1];
    last.extend_from_slice(&payload[20..]);
    let mut rx = Receiver::new(9);
    assert!(rx.receive(9, 0, &first).unwrap().message.is_none());
    let result = rx.receive(9, 1, &last).unwrap();
    assert!(result.ack_requested); // metadata only; no ACK is sent here
    let message = result.message.unwrap();
    assert!(matches!(
        decode_reply(message.endpoint, &message.payload),
        Ok(Reply::Challenge(_))
    ));
    assert!(matches!(
        Receiver::new(10).receive(9, 2, &last),
        Err(RxError::Session)
    ));
    first[1] |= 8;
    assert!(matches!(
        Receiver::new(9).receive(9, 0, &first),
        Err(RxError::EncryptedUnsupported)
    ));
}
