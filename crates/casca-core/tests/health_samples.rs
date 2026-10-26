use casca_core::health_samples::*;
use casca_core::ingestion::{Availability, Source, MAX_RECORDS};

fn synthetic_source() -> Source {
    Source {
        device_ref: "fixture-band7".into(),
        firmware: "fixture-firmware".into(),
        synthetic: true,
    }
}
fn hr_row(epoch: u32, quarter_offset: i8, hr: u8) -> Vec<u8> {
    let mut bytes = epoch.to_le_bytes().to_vec();
    bytes.extend_from_slice(&[quarter_offset as u8, hr]);
    bytes
}
fn spo2_row(epoch: i32, encoded: u8, unknown: [u8; 60]) -> Vec<u8> {
    let mut bytes = epoch.to_le_bytes().to_vec();
    bytes.push(encoded);
    bytes.extend_from_slice(&unknown);
    bytes
}
#[test]
fn resting_and_maximum_hr_are_separate_categories_with_full_provenance() {
    let mut payload = hr_row(1_700_000_000, -12, 255);
    payload.extend(hr_row(1_700_000_060, 4, 0));
    let Availability::Available(resting) =
        decode_heart_rate6(synthetic_source(), HeartRateKind::Resting, &payload).unwrap()
    else {
        panic!("expected resting HR records");
    };
    assert_eq!(resting.len(), 2);
    assert_eq!(resting[0].unix_seconds, 1_700_000_000);
    assert_eq!(resting[0].utc_offset_quarters, -12);
    assert_eq!(resting[0].heart_rate_code, 255);
    assert!(resting[0].source.synthetic);
    assert_eq!(resting[1].heart_rate_code, 0);
    assert_eq!(resting[1].utc_offset_quarters, 4);
    assert!(resting.iter().all(|r| r.kind == HeartRateKind::Resting));
    let Availability::Available(maximum) =
        decode_heart_rate6(synthetic_source(), HeartRateKind::Maximum, &payload).unwrap()
    else {
        panic!("expected maximum HR records");
    };
    assert!(maximum.iter().all(|r| r.kind == HeartRateKind::Maximum));
    assert_ne!(resting[0].kind, maximum[0].kind);
}
#[test]
fn hr_length_and_source_boundaries_are_enforced() {
    assert!(matches!(
        decode_heart_rate6(synthetic_source(), HeartRateKind::Resting, &[]),
        Ok(Availability::Absent)
    ));
    for bytes in [
        vec![0; 1],
        vec![0; 5],
        vec![0; 7],
        vec![0; (MAX_RECORDS + 1) * 6],
    ] {
        assert!(matches!(
            decode_heart_rate6(synthetic_source(), HeartRateKind::Maximum, &bytes),
            Err(DecodeError::Length)
        ));
    }
    let mut bad = synthetic_source();
    bad.device_ref = "../private".into();
    assert!(matches!(
        decode_heart_rate6(bad, HeartRateKind::Resting, &hr_row(1, 0, 70)),
        Err(DecodeError::Source)
    ));
    let Availability::Available(rows) = decode_heart_rate6(
        synthetic_source(),
        HeartRateKind::Maximum,
        &hr_row(u32::MAX, i8::MIN, 1),
    )
    .unwrap() else {
        panic!("expected max u32 timestamp");
    };
    assert_eq!(rows[0].unix_seconds, i64::from(u32::MAX));
    assert_eq!(rows[0].utc_offset_quarters, i8::MIN);
}
#[test]
fn spo2_v2_preserves_auto_flag_and_all_unknown_bytes() {
    let mut payload = vec![2_u8];
    let mut unknown = [0_u8; 60];
    unknown[0] = 0xde;
    unknown[59] = 0xad;
    payload.extend(spo2_row(1_700_000_000, 97, unknown));
    payload.extend(spo2_row(1_700_000_060, 98 | 0x80, [0xaa; 60]));
    let Availability::Available(rows) = decode_spo2_v2(synthetic_source(), &payload).unwrap()
    else {
        panic!("expected spo2 records");
    };
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].unix_seconds, 1_700_000_000);
    assert_eq!(rows[0].raw_value, 97);
    assert_eq!(rows[0].percent_code, 97);
    assert!(!rows[0].automatic);
    assert_eq!(rows[0].unknown[0], 0xde);
    assert_eq!(rows[0].unknown[59], 0xad);
    assert_eq!(rows[1].raw_value, 226);
    assert_eq!(rows[1].percent_code, 98);
    assert!(rows[1].automatic);
    assert_eq!(rows[1].unknown, [0xaa; 60]);
    assert!(rows[1].source.synthetic);
}
#[test]
fn spo2_v2_rejects_version_length_negative_time_without_partial_success() {
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &[2]),
        Ok(Availability::Absent)
    ));
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &[]),
        Err(DecodeError::Length)
    ));
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &[3]),
        Err(DecodeError::Version)
    ));
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &[2, 0]),
        Err(DecodeError::Length)
    ));
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &vec![2; 1 + 65 * (MAX_RECORDS + 1)]),
        Err(DecodeError::Length)
    ));
    let mut mixed = vec![2];
    mixed.extend(spo2_row(120, 96, [0; 60]));
    mixed.extend(spo2_row(-1, 95, [0; 60]));
    assert!(matches!(
        decode_spo2_v2(synthetic_source(), &mixed),
        Err(DecodeError::Time)
    ));
    let mut invalid_source = synthetic_source();
    invalid_source.firmware = "bad/firmware".into();
    assert!(matches!(
        decode_spo2_v2(invalid_source, &[2]),
        Err(DecodeError::Source)
    ));
}
