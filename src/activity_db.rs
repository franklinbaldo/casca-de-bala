// SPDX-License-Identifier: AGPL-3.0-or-later
//! Local SQLite persistence for the Activity8 decoder. No health export or BLE IO.
use crate::ingestion::{decode_activity8, Availability, IngestError, Record, Source, Window};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use std::{path::Path, time::Duration};

const APP_ID: i64 = 0x42374138;
#[derive(Debug, PartialEq, Eq)]
pub enum StoreError {
    Database,
    Cancelled,
    Schema,
    Ingestion(IngestError),
}
impl From<rusqlite::Error> for StoreError {
    fn from(_: rusqlite::Error) -> Self {
        Self::Database
    }
}
impl From<IngestError> for StoreError {
    fn from(error: IngestError) -> Self {
        Self::Ingestion(error)
    }
}
#[derive(Debug, PartialEq, Eq)]
pub struct Committed {
    pub inserted: usize,
    pub resume_from: i64,
}
/// One immutable source and expected window per handle. Multiple sources/windows
/// may share a DB; they never share cursor keys. No cached uncommitted cursor.
pub struct ActivityDb {
    connection: Connection,
    source: Source,
    start: i64,
    end: i64,
}
impl ActivityDb {
    pub fn open(path: &Path, source: Source, start: i64, end: i64) -> Result<Self, StoreError> {
        if path.as_os_str().is_empty()
            || path == Path::new(":memory:")
            || path.to_string_lossy().starts_with("file:")
        {
            return Err(StoreError::Schema);
        }
        Window::new(source.clone())?.missing_minutes(start, end)?;
        let mut connection = Connection::open(path)?;
        connection.busy_timeout(Duration::from_millis(100))?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let id: i64 = tx.query_row("PRAGMA application_id", [], |r| r.get(0))?;
        let version: i64 = tx.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        let tables: i64 = tx.query_row(
            "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
            [],
            |r| r.get(0),
        )?;
        if !((id == 0 && version == 0 && tables == 0) || (id == APP_ID && version == 1)) {
            return Err(StoreError::Schema);
        }
        tx.execute_batch("CREATE TABLE IF NOT EXISTS activity8 (
            device TEXT NOT NULL, firmware TEXT NOT NULL, synthetic INTEGER NOT NULL CHECK(synthetic IN(0,1)),
            ts INTEGER NOT NULL CHECK(ts>=0), utc_offset INTEGER,
            payload BLOB NOT NULL CHECK(length(payload)=8),
            PRIMARY KEY(device,firmware,synthetic,ts));
            CREATE TABLE IF NOT EXISTS checkpoints (
            device TEXT NOT NULL, firmware TEXT NOT NULL, synthetic INTEGER NOT NULL,
            start INTEGER NOT NULL, end INTEGER NOT NULL, cursor INTEGER NOT NULL,
            PRIMARY KEY(device,firmware,synthetic,start,end));
            PRAGMA application_id=1110917432; PRAGMA user_version=1;")?;
        tx.commit()?;
        // Use rollback journal with FULL sync; fsync guarantees still depend on OS/storage.
        connection.pragma_update(None, "journal_mode", "DELETE")?;
        connection.pragma_update(None, "synchronous", "FULL")?;
        Ok(Self {
            connection,
            source,
            start,
            end,
        })
    }
    pub fn records(&self) -> Result<Vec<Record>, StoreError> {
        load(&self.connection, &self.source, self.start, self.end)
    }
    pub fn cursor(&self) -> Result<Option<i64>, StoreError> {
        Ok(self.connection.query_row("SELECT cursor FROM checkpoints WHERE device=?1 AND firmware=?2 AND synthetic=?3 AND start=?4 AND end=?5",
            params![self.source.device_ref,self.source.firmware,self.source.synthetic,self.start,self.end], |r|r.get(0)).optional()?)
    }
    pub fn missing_minutes(&self) -> Result<Vec<i64>, StoreError> {
        let mut window = Window::new(self.source.clone())?;
        window.merge(self.records()?)?;
        Ok(window.missing_minutes(self.start, self.end)?)
    }
    /// A coherent read transaction, including writers outside this ABI process.
    pub fn coverage_snapshot(&mut self) -> Result<(Option<i64>, usize, usize), StoreError> {
        let tx = self.connection.transaction()?;
        let cursor = tx.query_row("SELECT cursor FROM checkpoints WHERE device=?1 AND firmware=?2 AND synthetic=?3 AND start=?4 AND end=?5", params![self.source.device_ref,self.source.firmware,self.source.synthetic,self.start,self.end], |r| r.get(0)).optional()?;
        let records = load(&tx, &self.source, self.start, self.end)?;
        let count = records.len();
        let mut window = Window::new(self.source.clone())?;
        window.merge(records)?;
        let missing = window.missing_minutes(self.start, self.end)?.len();
        tx.commit()?;
        Ok((cursor, count, missing))
    }
    /// Bytes must already be authenticated/decrypted by the future fetch layer.
    /// Empty response cannot advance across a missing minute. Returns only after COMMIT.
    pub fn ingest(
        &mut self,
        first: i64,
        offset: Option<i32>,
        bytes: &[u8],
    ) -> Result<Committed, StoreError> {
        self.ingest_checked(first, offset, bytes, || true)
    }
    /// Check cancellation after staging rows/checkpoint, just before COMMIT.
    /// False rolls back; a later cancellation cannot revoke a committed batch.
    pub fn ingest_checked(
        &mut self,
        first: i64,
        offset: Option<i32>,
        bytes: &[u8],
        permit_commit: impl FnOnce() -> bool,
    ) -> Result<Committed, StoreError> {
        let incoming = match decode_activity8(self.source.clone(), first, offset, bytes)? {
            Availability::Available(rows) => rows,
            Availability::Absent => Vec::new(),
            _ => unreachable!(),
        };
        if incoming.iter().any(|r| {
            r.unix_seconds < self.start
                || r.unix_seconds >= self.end
                || (r.unix_seconds - self.start) % 60 != 0
        }) {
            return Err(IngestError::Time.into());
        }
        let tx = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut window = Window::new(self.source.clone())?;
        window.merge(load(&tx, &self.source, self.start, self.end)?)?;
        let inserted = window.merge(incoming.clone())?;
        for row in incoming {
            let a = row.activity;
            let payload = [
                a.kind_code,
                a.intensity_code,
                a.steps_reported,
                a.heart_rate_code,
                a.unknown1,
                a.sleep_code,
                a.deep_sleep_code,
                a.rem_sleep_code,
            ];
            // Validate existing rows even if another window previously inserted them.
            let existing:Option<(Option<i32>,Vec<u8>)>=tx.query_row("SELECT utc_offset,payload FROM activity8 WHERE device=?1 AND firmware=?2 AND synthetic=?3 AND ts=?4",
                params![self.source.device_ref,self.source.firmware,self.source.synthetic,row.unix_seconds],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
            if let Some((old_offset, old_payload)) = existing {
                if old_offset != row.utc_offset_seconds || old_payload != payload {
                    return Err(IngestError::Conflict.into());
                }
            } else {
                tx.execute(
                    "INSERT INTO activity8 VALUES(?1,?2,?3,?4,?5,?6)",
                    params![
                        self.source.device_ref,
                        self.source.firmware,
                        self.source.synthetic,
                        row.unix_seconds,
                        row.utc_offset_seconds,
                        payload.as_slice()
                    ],
                )?;
            }
        }
        let resume_from = window.resume_from(self.start, self.end)?;
        tx.execute("INSERT INTO checkpoints VALUES(?1,?2,?3,?4,?5,?6) ON CONFLICT(device,firmware,synthetic,start,end) DO UPDATE SET cursor=excluded.cursor",
            params![self.source.device_ref,self.source.firmware,self.source.synthetic,self.start,self.end,resume_from])?;
        if !permit_commit() {
            return Err(StoreError::Cancelled);
        }
        tx.commit()?;
        Ok(Committed {
            inserted,
            resume_from,
        })
    }
}
fn load(
    connection: &Connection,
    source: &Source,
    start: i64,
    end: i64,
) -> Result<Vec<Record>, StoreError> {
    let mut query=connection.prepare("SELECT ts,utc_offset,payload FROM activity8 WHERE device=?1 AND firmware=?2 AND synthetic=?3 AND ts>=?4 AND ts<?5 ORDER BY ts LIMIT 4097")?;
    let values = query.query_map(
        params![
            source.device_ref,
            source.firmware,
            source.synthetic,
            start,
            end
        ],
        |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, Option<i32>>(1)?,
                row.get::<_, Vec<u8>>(2)?,
            ))
        },
    )?;
    let mut records = Vec::new();
    for value in values {
        let (time, offset, payload) = value?;
        if payload.len() != 8 {
            return Err(StoreError::Schema);
        }
        if let Availability::Available(mut row) =
            decode_activity8(source.clone(), time, offset, &payload)?
        {
            records.append(&mut row);
        }
    }
    // Reuse bounded semantic validation after loading, detecting invalid/corrupt windows.
    let mut window = Window::new(source.clone())?;
    window.merge(records.clone())?;
    window.missing_minutes(start, end)?;
    Ok(records)
}
