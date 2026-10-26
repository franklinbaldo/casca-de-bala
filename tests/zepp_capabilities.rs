use band7_terminal::zepp_capabilities::*;
use std::collections::BTreeMap;
#[test]
fn primary_request_bytes_and_cipher_gate() {
    for (op, endpoint, byte, effect) in [
        (Operation::BatteryRead, 0x29, 3, Effect::ReadOnly),
        (Operation::FindStart, 0x1a, 3, Effect::HapticOutput),
        (Operation::FindStop, 0x1a, 6, Effect::HapticOutput),
        (
            Operation::NotificationCapabilities,
            0x1e,
            1,
            Effect::ReadOnly,
        ),
    ] {
        let cmd = prepare(op);
        assert_eq!(cmd.endpoint(), endpoint);
        assert_eq!(cmd.payload(), &[byte]);
        assert_eq!(cmd.effect(), effect);
        assert!(!cmd.executable());
        assert_eq!(cmd.resolve_cipher(None), Err(Error::DiscoveryRequired));
        let mut map = BTreeMap::new();
        assert_eq!(cmd.resolve_cipher(Some(&map)), Err(Error::ServiceAbsent));
        map.insert(endpoint, None);
        assert_eq!(
            cmd.resolve_cipher(Some(&map)),
            Err(Error::UnknownEncryption)
        );
        map.insert(endpoint, Some(true));
        assert_eq!(cmd.resolve_cipher(Some(&map)), Ok(Cipher::Encrypted));
        assert_eq!(
            cmd.frame_plain_for_simulation(1, 23, Some(&map)),
            Err(Error::EncryptedFramingRequired)
        );
        map.insert(endpoint, Some(false));
        let frames = cmd.frame_plain_for_simulation(1, 23, Some(&map)).unwrap();
        let mut rx = band7_terminal::huami_chunk::Receiver::new(1);
        let msg = rx.receive(1, 0, &frames[0]).unwrap().message.unwrap();
        assert_eq!(msg.endpoint, endpoint);
        assert_eq!(msg.payload, vec![byte]);
    }
}
#[test]
fn battery21_is_not_gatt_percent_or_an_invented_timestamp() {
    let mut b = [0; 21];
    b[0] = 4;
    b[2] = 75;
    b[3] = 1;
    b[11..19].copy_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
    b[20] = 100;
    let v = decode_battery(&b).unwrap();
    assert_eq!(v.percent, 75);
    assert_eq!(v.charging, Charging::Charging);
    assert_eq!(v.last_charge_percent, ReportedPercent::Known(100));
    assert_eq!(v.last_charge_device_time_raw, [1, 2, 3, 4, 5, 6, 7, 8]);
    assert_eq!(v.raw_payload, b);
    b[3] = 255;
    b[20] = 255;
    let v = decode_battery(&b).unwrap();
    assert_eq!(v.charging, Charging::Unknown(255));
    assert_eq!(v.last_charge_percent, ReportedPercent::Unknown(255));
    for n in 0..21 {
        assert!(decode_battery(&b[..n]).is_err());
    }
    assert!(decode_battery(&[0; 22]).is_err());
    b[2] = 101;
    assert_eq!(decode_battery(&b), Err(Error::Range));
    b[2] = 0;
    assert_eq!(decode_battery(&b).unwrap().percent, 0);
    b[0] = 3;
    assert_eq!(decode_battery(&b), Err(Error::Opcode));
}
#[test]
fn find_ack_never_proves_motor_output() {
    assert_eq!(
        decode_find_event(&[4]).unwrap(),
        FindEvent::Acknowledged {
            uninterpreted: vec![]
        }
    );
    assert_eq!(
        decode_find_event(&[4, 255]).unwrap(),
        FindEvent::Acknowledged {
            uninterpreted: vec![255]
        }
    );
    assert_eq!(
        decode_find_event(&[7]).unwrap(),
        FindEvent::StoppedByBand {
            uninterpreted: vec![]
        }
    );
    assert_eq!(decode_find_event(&[0x11]), Err(Error::Opcode));
    assert!(decode_find_event(&[]).is_err());
    assert!(decode_find_event(&[4; 33]).is_err());
}
#[test]
fn notification_capabilities_versions_and_bounds() {
    let v4 = [2, 4, 100, 0, 1, 1, 2, 0, 0xaa, 0xbb];
    let v = decode_notification_capabilities(&v4).unwrap();
    assert_eq!(v.version, 4);
    assert_eq!(v.supports_pictures, None);
    assert_eq!(v.opaque_descriptors, vec![0xaa, 0xbb]);
    assert_eq!(v.uninterpreted_word, 100);
    let v5 = [2, 5, 100, 0, 1, 1, 0, 0, 1, 0];
    let v = decode_notification_capabilities(&v5).unwrap();
    assert_eq!(v.supports_pictures, Some(true));
    assert_eq!(v.supports_notification_key, Some(false));
    for n in 0..v5.len() {
        assert!(decode_notification_capabilities(&v5[..n]).is_err());
    }
    let mut trailing = v5.to_vec();
    trailing.push(0);
    assert!(decode_notification_capabilities(&trailing).is_err());
    assert_eq!(
        decode_notification_capabilities(&[2, 3]),
        Err(Error::UnsupportedVersion(3))
    );
    assert_eq!(
        decode_notification_capabilities(&[2, 6]),
        Err(Error::UnsupportedVersion(6))
    );
    assert_eq!(
        decode_notification_capabilities(&[2, 4, 0, 0, 0, 0, 1, 1]),
        Err(Error::Limit)
    );
}
