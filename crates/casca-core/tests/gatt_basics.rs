use casca_core::{gatt_basics::*, ingestion::Availability};
fn d(s: u16, c: u16, readable: bool) -> Discovered {
    Discovered {
        service: Uuid::Bluetooth16(s),
        characteristic: Uuid::Bluetooth16(c),
        readable,
    }
}
fn hex(value: &str) -> Vec<u8> {
    value
        .as_bytes()
        .chunks(2)
        .map(|p| u8::from_str_radix(std::str::from_utf8(p).unwrap(), 16).unwrap())
        .collect()
}
#[test]
fn sanitized_user_reads_are_not_our_app_reads() {
    let fixture: serde_json::Value =
        serde_json::from_str(include_str!("../../../fixtures/user-gatt-sanitized.json")).unwrap();
    let kinds = [
        Kind::HardwareRevision,
        Kind::SoftwareRevision,
        Kind::PnpId,
        Kind::Battery,
    ];
    for (row, kind) in fixture["reads"].as_array().unwrap().iter().zip(kinds) {
        let data = hex(row["hex"].as_str().unwrap());
        let state = interpret(
            kind,
            Reader::UserNrfConnect,
            ReadOutcome::Gatt {
                status: 0,
                bytes: &data,
            },
        );
        assert!(matches!(
            state,
            ReadState::Read {
                reader: Reader::UserNrfConnect,
                ..
            }
        ));
        if let Some(text) = row["text"].as_str() {
            assert_eq!(decode(kind, &data), Ok(Value::Revision(text.into())));
        }
    }
    let pnp = match decode(Kind::PnpId, &hex("01570167000201")).unwrap() {
        Value::Pnp(p) => p,
        _ => panic!("wrong type"),
    };
    assert_eq!(
        (
            pnp.vendor_source,
            pnp.vendor_id,
            pnp.product_id,
            pnp.product_version_bcd
        ),
        (1, 0x157, 0x67, 0x102)
    );
    assert_eq!(pnp.version(), (1, 0, 2));
    assert!(matches!(device_source(&pnp), Availability::Unknown));
    assert_eq!(
        decode(Kind::Battery, &[0x5b]),
        Ok(Value::BatteryPercent(91))
    );
    assert_eq!(fixture["our_app_physical_reads"], false);
}
#[test]
fn decoder_boundaries_and_invalid_data() {
    for n in [0, 1, 6, 8] {
        assert_eq!(decode(Kind::PnpId, &vec![0; n]), Err(Error::Length));
    }
    for source in [0, 3, 255] {
        let mut b = hex("01570167000201");
        b[0] = source;
        assert_eq!(decode(Kind::PnpId, &b), Err(Error::VendorSource));
    }
    let mut b = hex("01570167000201");
    b[5] = 0xfa;
    assert_eq!(decode(Kind::PnpId, &b), Err(Error::Bcd));
    for percent in [0, 100] {
        assert_eq!(
            decode(Kind::Battery, &[percent]),
            Ok(Value::BatteryPercent(percent))
        );
    }
    for percent in [101, 255] {
        assert_eq!(decode(Kind::Battery, &[percent]), Err(Error::Percent));
    }
    assert_eq!(decode(Kind::Battery, &[]), Err(Error::Length));
    assert_eq!(decode(Kind::Battery, &[1, 2]), Err(Error::Length));
    for text in [vec![], vec![b'x'; 65]] {
        assert_eq!(decode(Kind::HardwareRevision, &text), Err(Error::Length));
    }
    assert_eq!(decode(Kind::SoftwareRevision, &[0xff]), Err(Error::Utf8));
    for text in [b"a\0".as_slice(), b"\n", b" "] {
        assert_eq!(decode(Kind::SoftwareRevision, text), Err(Error::Revision));
    }
    assert!(decode(Kind::HardwareRevision, &[b'x'; 64]).is_ok());
}
#[test]
fn planning_never_reads_hid_identifiers_or_proprietary_aliases() {
    let basic = d(0x180a, 0x2a27, true);
    let discovered = [
        basic,
        d(0x180a, 0x2a28, false),
        d(0x180f, 0x2a19, true),
        d(0x180d, 0x2a37, false),
        d(0x1812, 0x2a4b, true),
        d(0x180a, 0x2a25, true),
        Discovered {
            service: Uuid::Bluetooth16(0x180a),
            characteristic: Uuid::Vendor128([0x27; 16]),
            readable: true,
        },
    ];
    assert_eq!(
        plan(&discovered, true).unwrap(),
        vec![
            Decision::Read(Kind::HardwareRevision),
            Decision::Skip(Skip::NotReadable),
            Decision::Read(Kind::Battery),
            Decision::Skip(Skip::OutsideBasicScope),
            Decision::Skip(Skip::PrivilegedHid),
            Decision::Skip(Skip::SensitiveIdentifier),
            Decision::Skip(Skip::OutsideBasicScope)
        ]
    );
    assert_eq!(
        plan(&[basic], false).unwrap(),
        vec![Decision::Skip(Skip::ConnectPermission)]
    );
    assert_eq!(plan(&vec![basic; 129], true), Err(Error::DiscoveryLimit));
    assert_eq!(
        plan(&[d(0x180a, 0x2a19, true)], true).unwrap(),
        vec![Decision::Skip(Skip::OutsideBasicScope)]
    );
}
#[test]
fn discovered_denied_failed_invalid_and_read_are_distinct() {
    assert_eq!(
        interpret(Kind::Battery, Reader::OurApp, ReadOutcome::NotAttempted),
        ReadState::NotRead
    );
    assert_eq!(
        interpret(Kind::Battery, Reader::OurApp, ReadOutcome::PermissionDenied),
        ReadState::PermissionDenied
    );
    assert_eq!(
        interpret(
            Kind::Battery,
            Reader::OurApp,
            ReadOutcome::Gatt {
                status: 5,
                bytes: &[91]
            }
        ),
        ReadState::GattFailure(5)
    );
    assert_eq!(
        interpret(
            Kind::Battery,
            Reader::OurApp,
            ReadOutcome::Gatt {
                status: 0,
                bytes: &[]
            }
        ),
        ReadState::InvalidValue(Error::Length)
    );
}
