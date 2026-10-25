use band7_terminal::{
    event_db::{EventDb, EventKind, EventStoreError},
    health_samples::{DecodeError, HeartRateKind},
    ingestion::{IngestError, Source},
};
use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let directory = std::env::temp_dir().join(format!(
            "band7-events-synthetic-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&directory).unwrap();
        Self(directory)
    }
    fn path(&self) -> PathBuf {
        self.0.join("events.sqlite")
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn source() -> Source {
    Source {
        device_ref: "fixture-band".into(),
        firmware: "synthetic-fw".into(),
        synthetic: true,
    }
}
fn hr(time: u32, offset: i8, value: u8) -> Vec<u8> {
    let mut row = time.to_le_bytes().to_vec();
    row.push(offset as u8);
    row.push(value);
    row
}
fn spo2(time: i32, value: u8, extra: u8) -> Vec<u8> {
    let mut row = time.to_le_bytes().to_vec();
    row.push(value);
    row.extend([extra; 60]);
    row
}

#[test]
fn resting_and_maximum_reopen_without_mixing_and_replay_is_idempotent() {
    let f = Fixture::new();
    let payload = hr(1700000000, -12, 255);
    {
        let mut db = EventDb::open(&f.path(), source()).unwrap();
        assert_eq!(db.ingest_hr(HeartRateKind::Resting, &payload).unwrap(), 1);
        assert_eq!(db.ingest_hr(HeartRateKind::Resting, &payload).unwrap(), 0);
        assert_eq!(db.ingest_hr(HeartRateKind::Maximum, &payload).unwrap(), 1);
    }
    let db = EventDb::open(&f.path(), source()).unwrap();
    let resting = db
        .records(EventKind::RestingHeartRate, 1699999999, 1700000001)
        .unwrap();
    assert_eq!(resting.len(), 1);
    assert_eq!(resting[0].payload, payload);
    assert!(resting[0].source == source());
    assert_eq!(resting[0].unix_seconds, 1700000000);
    assert_eq!(resting[0].kind, EventKind::RestingHeartRate);
    let max = db
        .records(EventKind::MaximumHeartRate, 0, 1700000001)
        .unwrap();
    assert_eq!(max.len(), 1);
    assert_eq!(max[0].kind, EventKind::MaximumHeartRate);
    assert_eq!(max[0].payload, resting[0].payload);
}

#[test]
fn spo2_preserves_unknown_bytes_and_same_second_distinct_readings() {
    let f = Fixture::new();
    let mut db = EventDb::open(&f.path(), source()).unwrap();
    let mut packet = vec![2];
    packet.extend(spo2(1700000020, 0x80 | 98, 0xab));
    packet.extend(spo2(1700000020, 97, 0xcd));
    assert_eq!(db.ingest_spo2_v2(&packet).unwrap(), 2);
    assert_eq!(db.ingest_spo2_v2(&packet).unwrap(), 0);
    let records = db
        .records(EventKind::NormalSpo2V2, 1700000020, 1700000021)
        .unwrap();
    assert_eq!(records.len(), 2);
    assert!(records
        .iter()
        .any(|record| record.payload[4] == 226
            && record.payload[5..].iter().all(|byte| *byte == 0xab)));
    assert!(records
        .iter()
        .any(|record| record.payload[4] == 97
            && record.payload[5..].iter().all(|byte| *byte == 0xcd)));
}

#[test]
fn invalid_payload_and_source_never_commit_partial_records() {
    let f = Fixture::new();
    let mut db = EventDb::open(&f.path(), source()).unwrap();
    assert!(matches!(
        db.ingest_hr(HeartRateKind::Resting, &[1, 2]),
        Err(EventStoreError::Decode(DecodeError::Length))
    ));
    assert!(matches!(
        db.ingest_spo2_v2(&[3]),
        Err(EventStoreError::Decode(DecodeError::Version))
    ));
    let mut packet = vec![2];
    packet.extend(spo2(42, 100, 0));
    packet.extend(spo2(-1, 95, 0));
    assert!(matches!(
        db.ingest_spo2_v2(&packet),
        Err(EventStoreError::Decode(DecodeError::Time))
    ));
    assert!(db
        .records(EventKind::NormalSpo2V2, 0, 100)
        .unwrap()
        .is_empty());
    assert!(db.records(EventKind::RestingHeartRate, -1, 100).is_err());
    let mut invalid = source();
    invalid.device_ref = "../secret".into();
    assert!(matches!(
        EventDb::open(&f.path(), invalid),
        Err(EventStoreError::Source(IngestError::Source))
    ));
}

#[test]
fn failure_in_later_insert_rolls_back_entire_batch() {
    let f = Fixture::new();
    let mut db = EventDb::open(&f.path(), source()).unwrap();
    let external = rusqlite::Connection::open(f.path()).unwrap();
    external
        .execute_batch(
            "CREATE TRIGGER fail_second BEFORE INSERT ON event_samples
         WHEN NEW.ts = 202 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END;",
        )
        .unwrap();
    let mut packet = vec![2];
    packet.extend(spo2(201, 95, 1));
    packet.extend(spo2(202, 94, 2));
    assert_eq!(db.ingest_spo2_v2(&packet), Err(EventStoreError::Database));
    drop(db);
    let mut reopened = EventDb::open(&f.path(), source()).unwrap();
    assert!(reopened
        .records(EventKind::NormalSpo2V2, 0, 300)
        .unwrap()
        .is_empty());
    external.execute_batch("DROP TRIGGER fail_second;").unwrap();
    assert_eq!(reopened.ingest_spo2_v2(&packet).unwrap(), 2);
}

#[test]
fn schema_and_sources_are_isolated_and_locked_writes_do_not_succeed() {
    let f = Fixture::new();
    let mut db = EventDb::open(&f.path(), source()).unwrap();
    let payload = hr(45, 0, 70);
    db.ingest_hr(HeartRateKind::Resting, &payload).unwrap();
    let mut another = source();
    another.firmware = "fixture-other".into();
    assert!(EventDb::open(&f.path(), another)
        .unwrap()
        .records(EventKind::RestingHeartRate, 0, 50)
        .unwrap()
        .is_empty());
    let external = rusqlite::Connection::open(f.path()).unwrap();
    external.execute_batch("BEGIN IMMEDIATE;").unwrap();
    assert_eq!(
        db.ingest_hr(HeartRateKind::Maximum, &payload),
        Err(EventStoreError::Database)
    );
    external
        .execute_batch("ROLLBACK;PRAGMA user_version=999;")
        .unwrap();
    drop(db);
    assert!(matches!(
        EventDb::open(&f.path(), source()),
        Err(EventStoreError::Schema)
    ));
}
