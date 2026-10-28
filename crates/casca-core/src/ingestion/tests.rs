use super::*;
fn source() -> Source {
    Source {
        device_ref: "synthetic-band".into(),
        firmware: "fixture-v1".into(),
        synthetic: true,
    }
}
fn records(start: i64, data: &[u8]) -> Vec<Record> {
    match decode_activity8(source(), start, Some(-10800), data).unwrap() {
        Availability::Available(v) => v,
        _ => panic!("expected records"),
    }
}
#[test]
fn real_layout_minute_cadence_and_provenance_without_guessing_sentinels() {
    let rows = records(
        120,
        &[1, 2, 3, 255, 5, 6, 7, 8, 10, 11, 12, 0, 14, 15, 16, 17],
    );
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[1].unix_seconds, 180);
    assert_eq!(rows[0].utc_offset_seconds, Some(-10800));
    assert!(rows[0].source.synthetic);
    assert_eq!(rows[0].activity.heart_rate_code, 255);
    assert_eq!(rows[1].activity.heart_rate_code, 0);
    assert_eq!(rows[0].activity.steps_reported, 3);
    assert_eq!(rows[0].activity.unknown1, 5);
    assert_eq!(rows[1].activity.rem_sleep_code, 17);
}
#[test]
fn status_and_bounds_are_distinct() {
    assert!(matches!(
        decode_activity8(source(), 0, None, &[]).unwrap(),
        Availability::Absent
    ));
    let statuses = [
        Availability::<Vec<Record>>::Absent,
        Availability::Unsupported,
        Availability::Unimplemented,
        Availability::Unknown,
    ];
    for (i, a) in statuses.iter().enumerate() {
        for (j, b) in statuses.iter().enumerate() {
            assert_eq!(a == b, i == j);
        }
    }
    for n in [1, 7, 9, MAX_RECORDS * 8 + 8] {
        assert!(matches!(
            decode_activity8(source(), 0, None, &vec![0; n]),
            Err(IngestError::Length)
        ));
    }
    assert!(decode_activity8(source(), i64::MAX, None, &[0; 16]).is_err());
    assert!(decode_activity8(source(), -1, None, &[0; 8]).is_err());
    assert!(decode_activity8(source(), 0, Some(86401), &[0; 8]).is_err());
    let mut bad = source();
    bad.device_ref = "../bad".into();
    assert!(Window::new(bad).is_err());
}
#[test]
fn overlap_late_arrival_gap_and_contiguous_resume() {
    let mut window = Window::new(source()).unwrap();
    assert_eq!(window.merge(records(120, &[1; 8])).unwrap(), 1);
    assert_eq!(window.merge(records(240, &[1; 8])).unwrap(), 1);
    assert_eq!(window.merge(records(120, &[1; 8])).unwrap(), 0);
    assert_eq!(window.missing_minutes(120, 300).unwrap(), vec![180]);
    assert_eq!(window.resume_from(120, 300).unwrap(), 180);
    window.merge(records(180, &[1; 8])).unwrap();
    assert_eq!(window.resume_from(120, 300).unwrap(), 300);
}
#[test]
fn conflict_scope_and_time_errors_leave_window_unchanged() {
    let mut w = Window::new(source()).unwrap();
    w.merge(records(120, &[1; 8])).unwrap();
    let mut batch = records(180, &[1; 8]);
    batch.extend(records(120, &[2; 8]));
    assert_eq!(w.merge(batch), Err(IngestError::Conflict));
    assert_eq!(w.len(), 1);
    let mut other = records(180, &[1; 8]);
    other[0].source.firmware = "another".into();
    assert_eq!(w.merge(other), Err(IngestError::Scope));
    assert_eq!(w.merge(records(181, &[1; 8])), Err(IngestError::Time));
    for (a, b) in [(120, 119), (120, 121), (121, 181), (0, i64::MAX)] {
        assert!(w.missing_minutes(a, b).is_err());
    }
    assert_eq!(w.len(), 1);
}
#[test]
fn capacity_never_evicts_or_advances_sync_silently() {
    let mut w = Window::new(source()).unwrap();
    w.merge(records(0, &vec![1; MAX_RECORDS * 8])).unwrap();
    assert_eq!(
        w.merge(records(MAX_RECORDS as i64 * 60, &[1; 8])),
        Err(IngestError::Capacity)
    );
    assert_eq!(w.len(), MAX_RECORDS);
}
