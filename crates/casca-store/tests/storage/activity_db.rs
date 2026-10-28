use casca_core::ingestion::*;
use casca_store::activity_db::*;
use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "b7-synthetic-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
    fn path(&self) -> PathBuf {
        self.0.join("synthetic.sqlite")
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn source() -> Source {
    Source {
        device_ref: "fixture".into(),
        firmware: "test-1".into(),
        synthetic: true,
    }
}
fn open(f: &Fixture) -> ActivityDb {
    ActivityDb::open(&f.path(), source(), 120, 300).unwrap()
}
#[test]
fn bytes_to_decoder_to_sqlite_reopen_query_and_resume() {
    let f = Fixture::new();
    {
        let mut db = open(&f);
        assert_eq!(db.cursor().unwrap(), None);
        assert_eq!(
            db.ingest(120, Some(-10800), &[1, 2, 3, 255, 5, 6, 7, 8])
                .unwrap(),
            Committed {
                inserted: 1,
                resume_from: 180
            }
        );
        assert_eq!(
            db.ingest(240, None, &[10, 11, 12, 0, 14, 15, 16, 17])
                .unwrap()
                .resume_from,
            180
        );
    }
    let mut db = open(&f);
    assert_eq!(db.cursor().unwrap(), Some(180));
    assert_eq!(db.missing_minutes().unwrap(), vec![180]);
    let rows = db.records().unwrap();
    assert_eq!(rows.len(), 2);
    let mut expected = Vec::new();
    for (time, offset, data) in [
        (120, Some(-10800), vec![1, 2, 3, 255, 5, 6, 7, 8]),
        (240, None, vec![10, 11, 12, 0, 14, 15, 16, 17]),
    ] {
        if let Availability::Available(mut records) =
            decode_activity8(source(), time, offset, &data).unwrap()
        {
            expected.append(&mut records);
        }
    }
    assert!(rows == expected);
    assert!(rows[0].source == source());
    assert_eq!(rows[0].utc_offset_seconds, Some(-10800));
    assert_eq!(rows[1].utc_offset_seconds, None);
    assert_eq!(rows[0].activity.heart_rate_code, 255);
    assert_eq!(rows[1].activity.rem_sleep_code, 17);
    assert_eq!(
        db.ingest(120, Some(-10800), &[1, 2, 3, 255, 5, 6, 7, 8])
            .unwrap()
            .inserted,
        0
    );
    db.ingest(180, Some(0), &[2; 8]).unwrap();
    drop(db);
    let db = open(&f);
    assert_eq!(db.cursor().unwrap(), Some(300));
    assert!(db.missing_minutes().unwrap().is_empty());
}
#[test]
fn conflict_or_invalid_payload_never_changes_rows_or_cursor() {
    let f = Fixture::new();
    let mut db = open(&f);
    db.ingest(180, None, &[1; 8]).unwrap();
    assert_eq!(
        db.ingest(120, None, &[2; 16]),
        Err(StoreError::Ingestion(IngestError::Conflict))
    );
    assert!(db.ingest(120, None, &[1; 7]).is_err());
    assert!(db.ingest(119, None, &[1; 8]).is_err());
    assert_eq!(db.cursor().unwrap(), Some(120));
    assert_eq!(db.records().unwrap().len(), 1);
    drop(db);
    let db = open(&f);
    assert_eq!(db.cursor().unwrap(), Some(120));
    assert_eq!(db.records().unwrap().len(), 1);
}
#[test]
fn sql_failure_after_rows_inserted_rolls_back_checkpoint_and_batch() {
    let f = Fixture::new();
    let mut db = open(&f);
    db.ingest(120, None, &[1; 8]).unwrap();
    let external = rusqlite::Connection::open(f.path()).unwrap();
    external.execute_batch("CREATE TRIGGER fail_checkpoint BEFORE INSERT ON checkpoints BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").unwrap();
    assert_eq!(db.ingest(180, None, &[1; 16]), Err(StoreError::Database));
    assert_eq!(db.records().unwrap().len(), 1);
    assert_eq!(db.cursor().unwrap(), Some(180));
    drop(db);
    external
        .execute_batch("DROP TRIGGER fail_checkpoint;")
        .unwrap();
    drop(external);
    let mut db = open(&f);
    assert_eq!(db.cursor().unwrap(), Some(180));
    db.ingest(180, None, &[1; 16]).unwrap();
    assert_eq!(db.cursor().unwrap(), Some(300));
}
#[test]
fn sources_synthetic_flags_and_firmware_are_isolated() {
    let f = Fixture::new();
    let mut db = open(&f);
    db.ingest(120, None, &[1; 24]).unwrap();
    drop(db);
    for i in 0..3 {
        let mut s = source();
        match i {
            0 => s.device_ref = "other".into(),
            1 => s.firmware = "test-2".into(),
            _ => s.synthetic = false,
        }
        let mut isolated = ActivityDb::open(&f.path(), s, 120, 300).unwrap();
        assert!(isolated.records().unwrap().is_empty());
        assert_eq!(isolated.ingest(120, None, &[]).unwrap().resume_from, 120);
    }
    assert_eq!(open(&f).cursor().unwrap(), Some(300));
}
#[test]
fn refuses_unknown_schema_and_handles_write_lock_without_advancing() {
    let f = Fixture::new();
    let mut db = open(&f);
    let external = rusqlite::Connection::open(f.path()).unwrap();
    external.execute_batch("BEGIN IMMEDIATE;").unwrap();
    assert_eq!(db.ingest(120, None, &[1; 8]), Err(StoreError::Database));
    assert_eq!(db.cursor().unwrap(), None);
    external
        .execute_batch("ROLLBACK;PRAGMA user_version=999;")
        .unwrap();
    drop(db);
    drop(external);
    assert!(matches!(
        ActivityDb::open(&f.path(), source(), 120, 300),
        Err(StoreError::Schema)
    ));
}
