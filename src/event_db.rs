// SPDX-License-Identifier: AGPL-3.0-or-later
//! Local-only, unencrypted SQLite reference persistence for event-like HR/SpO2
//! records. No Android integration, BLE, network, logging, or MCP export.
use crate::health_samples::{decode_heart_rate6, decode_spo2_v2, DecodeError, HeartRateKind};
use crate::ingestion::{Availability, IngestError, Source, Window, MAX_RECORDS};
use rusqlite::{params, Connection, TransactionBehavior};
use std::{path::Path, time::Duration};

const APP_ID: i64 = 0x42374556; // "B7EV", deliberately distinct from ActivityDb.
const VERSION: i64 = 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EventKind {
    RestingHeartRate,
    MaximumHeartRate,
    NormalSpo2V2,
}
impl EventKind {
    fn tag(self) -> &'static str {
        match self {
            Self::RestingHeartRate => "hr_resting_6",
            Self::MaximumHeartRate => "hr_maximum_6",
            Self::NormalSpo2V2 => "spo2_normal_v2",
        }
    }
}
#[derive(Clone, PartialEq, Eq)]
pub struct StoredEvent {
    pub source: Source,
    pub kind: EventKind,
    pub unix_seconds: i64,
    /// Exact six- or sixty-five-byte record; no interpretation of unknown fields.
    pub payload: Vec<u8>,
}
#[derive(Debug, PartialEq, Eq)]
pub enum EventStoreError {
    Database,
    Schema,
    InvalidRange,
    Capacity,
    Source(IngestError),
    Decode(DecodeError),
}
impl From<rusqlite::Error> for EventStoreError {
    fn from(_: rusqlite::Error) -> Self {
        Self::Database
    }
}
impl From<IngestError> for EventStoreError {
    fn from(e: IngestError) -> Self {
        Self::Source(e)
    }
}
impl From<DecodeError> for EventStoreError {
    fn from(e: DecodeError) -> Self {
        Self::Decode(e)
    }
}

pub struct EventDb {
    connection: Connection,
    source: Source,
}
impl EventDb {
    /// Use an app-private file distinct from ActivityDb's SQLite file.
    /// Never accept a path selected by the remote caller or an unencrypted
    /// database for real personal data before host protection gates are met.
    pub fn open(path: &Path, source: Source) -> Result<Self, EventStoreError> {
        if path.as_os_str().is_empty()
            || path == Path::new(":memory:")
            || path.to_string_lossy().starts_with("file:")
        {
            return Err(EventStoreError::Schema);
        }
        Window::new(source.clone())?;
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
        if !((id == 0 && version == 0 && tables == 0) || (id == APP_ID && version == VERSION)) {
            return Err(EventStoreError::Schema);
        }
        tx.execute_batch(
            "CREATE TABLE IF NOT EXISTS event_samples (
                device TEXT NOT NULL,
                firmware TEXT NOT NULL,
                synthetic INTEGER NOT NULL CHECK(synthetic IN (0,1)),
                category TEXT NOT NULL CHECK(category IN ('hr_resting_6','hr_maximum_6','spo2_normal_v2')),
                ts INTEGER NOT NULL CHECK(ts >= 0),
                payload BLOB NOT NULL CHECK(
                    (category IN ('hr_resting_6','hr_maximum_6') AND length(payload)=6)
                    OR (category='spo2_normal_v2' AND length(payload)=65)
                ),
                PRIMARY KEY(device, firmware, synthetic, category, ts, payload)
            ) WITHOUT ROWID;",
        )?;
        tx.pragma_update(None, "application_id", APP_ID)?;
        tx.pragma_update(None, "user_version", VERSION)?;
        tx.commit()?;
        connection.pragma_update(None, "journal_mode", "DELETE")?;
        connection.pragma_update(None, "synchronous", "FULL")?;
        Ok(Self { connection, source })
    }

    pub fn ingest_hr(
        &mut self,
        kind: HeartRateKind,
        bytes: &[u8],
    ) -> Result<usize, EventStoreError> {
        let rows = match decode_heart_rate6(self.source.clone(), kind, bytes)? {
            Availability::Available(records) => records,
            Availability::Absent => return Ok(0),
            _ => unreachable!(),
        };
        let category = match kind {
            HeartRateKind::Resting => EventKind::RestingHeartRate,
            HeartRateKind::Maximum => EventKind::MaximumHeartRate,
        };
        let items: Vec<_> = rows
            .iter()
            .zip(
                bytes
                    .as_chunks::<6>()
                    .0
                    .iter()
                    .map(|chunk| chunk.as_slice()),
            )
            .map(|(record, payload)| (record.unix_seconds, payload))
            .collect();
        self.insert_batch(category, &items)
    }

    pub fn ingest_spo2_v2(&mut self, bytes: &[u8]) -> Result<usize, EventStoreError> {
        let rows = match decode_spo2_v2(self.source.clone(), bytes)? {
            Availability::Available(records) => records,
            Availability::Absent => return Ok(0),
            _ => unreachable!(),
        };
        let items: Vec<_> = rows
            .iter()
            .zip(
                bytes[1..]
                    .as_chunks::<65>()
                    .0
                    .iter()
                    .map(|chunk| chunk.as_slice()),
            )
            .map(|(record, payload)| (record.unix_seconds, payload))
            .collect();
        self.insert_batch(EventKind::NormalSpo2V2, &items)
    }

    /// A confirmed transaction inserts exact records or no records. It never
    /// advances an inferred "latest timestamp" cursor for irregular data.
    fn insert_batch(
        &mut self,
        category: EventKind,
        items: &[(i64, &[u8])],
    ) -> Result<usize, EventStoreError> {
        let tx = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut inserted = 0;
        for (timestamp, payload) in items {
            inserted += tx.execute(
                "INSERT INTO event_samples(device,firmware,synthetic,category,ts,payload)
                 VALUES (?1,?2,?3,?4,?5,?6)
                 ON CONFLICT(device,firmware,synthetic,category,ts,payload) DO NOTHING",
                params![
                    self.source.device_ref,
                    self.source.firmware,
                    self.source.synthetic,
                    category.tag(),
                    timestamp,
                    payload
                ],
            )?;
        }
        tx.commit()?;
        Ok(inserted)
    }

    /// Bounded queries only. Two distinct readings with the same timestamp are
    /// retained if their raw bytes differ; exact replay of identical bytes is a no-op.
    pub fn records(
        &self,
        category: EventKind,
        start: i64,
        end: i64,
    ) -> Result<Vec<StoredEvent>, EventStoreError> {
        if start < 0 || end < start {
            return Err(EventStoreError::InvalidRange);
        }
        let mut statement = self.connection.prepare(
            "SELECT ts,payload FROM event_samples
             WHERE device=?1 AND firmware=?2 AND synthetic=?3 AND category=?4
             AND ts>=?5 AND ts<?6 ORDER BY ts,payload LIMIT 4097",
        )?;
        let mut rows = statement.query(params![
            self.source.device_ref,
            self.source.firmware,
            self.source.synthetic,
            category.tag(),
            start,
            end
        ])?;
        let mut events = Vec::new();
        while let Some(row) = rows.next()? {
            if events.len() == MAX_RECORDS {
                return Err(EventStoreError::Capacity);
            }
            let time: i64 = row.get(0)?;
            let payload: Vec<u8> = row.get(1)?;
            let valid = match category {
                EventKind::RestingHeartRate | EventKind::MaximumHeartRate => {
                    let kind = if category == EventKind::RestingHeartRate {
                        HeartRateKind::Resting
                    } else {
                        HeartRateKind::Maximum
                    };
                    matches!(decode_heart_rate6(self.source.clone(),kind,&payload)?,
                        Availability::Available(found) if found.len()==1 && found[0].unix_seconds==time)
                }
                EventKind::NormalSpo2V2 => {
                    let mut packet = Vec::with_capacity(66);
                    packet.push(2);
                    packet.extend_from_slice(&payload);
                    matches!(decode_spo2_v2(self.source.clone(), &packet)?,
                        Availability::Available(found) if found.len()==1 && found[0].unix_seconds==time)
                }
            };
            if !valid {
                return Err(EventStoreError::Schema);
            }
            events.push(StoredEvent {
                source: self.source.clone(),
                kind: category,
                unix_seconds: time,
                payload,
            });
        }
        Ok(events)
    }
}
