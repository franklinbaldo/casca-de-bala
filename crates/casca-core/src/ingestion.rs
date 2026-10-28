// SPDX-License-Identifier: AGPL-3.0-or-later
// Format reference: Gadgetbridge FetchActivityOperation, Copyright (C)
// its contributors. Rust ingestion implementation: workspace contributors, 2026.
//! Local-only ingestion of already decrypted Huami 8-byte activity records.
use std::collections::BTreeMap;

pub const MAX_RECORDS: usize = 4096;
#[derive(Clone, PartialEq, Eq)]
pub enum Availability<T> {
    Available(T),
    Absent,
    Unsupported,
    Unimplemented,
    Unknown,
}
#[derive(Clone, PartialEq, Eq)]
pub struct Source {
    pub device_ref: String,
    pub firmware: String,
    pub synthetic: bool,
}
#[derive(Clone, PartialEq, Eq)]
pub struct ActivityMinute {
    pub kind_code: u8,
    pub intensity_code: u8,
    pub steps_reported: u8,
    pub heart_rate_code: u8,
    pub unknown1: u8,
    pub sleep_code: u8,
    pub deep_sleep_code: u8,
    pub rem_sleep_code: u8,
}
#[derive(Clone, PartialEq, Eq)]
pub struct Record {
    pub source: Source,
    pub unix_seconds: i64,
    pub utc_offset_seconds: Option<i32>,
    pub activity: ActivityMinute,
}
#[derive(Debug, PartialEq, Eq)]
pub enum IngestError {
    Source,
    Length,
    Time,
    Scope,
    Conflict,
    Capacity,
}
fn valid_source(source: &Source) -> bool {
    [&source.device_ref, &source.firmware].iter().all(|v| {
        (1..=64).contains(&v.len())
            && v.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
    })
}
/// Caller supplies the fetch header timestamp normalized to Unix time, never
/// the host's current timezone by inference. None means unknown offset.
pub fn decode_activity8(
    source: Source,
    start: i64,
    offset: Option<i32>,
    bytes: &[u8],
) -> Result<Availability<Vec<Record>>, IngestError> {
    if !valid_source(&source) {
        return Err(IngestError::Source);
    }
    if !bytes.len().is_multiple_of(8) || bytes.len() / 8 > MAX_RECORDS {
        return Err(IngestError::Length);
    }
    if start < 0 || offset.is_some_and(|v| !(-86400..=86400).contains(&v)) {
        return Err(IngestError::Time);
    }
    let mut records = Vec::with_capacity(bytes.len() / 8);
    for (i, sample) in bytes.as_chunks::<8>().0.iter().enumerate() {
        let unix_seconds = start.checked_add(i as i64 * 60).ok_or(IngestError::Time)?;
        records.push(Record {
            source: source.clone(),
            unix_seconds,
            utc_offset_seconds: offset,
            activity: ActivityMinute {
                kind_code: sample[0],
                intensity_code: sample[1],
                steps_reported: sample[2],
                heart_rate_code: sample[3],
                unknown1: sample[4],
                sleep_code: sample[5],
                deep_sleep_code: sample[6],
                rem_sleep_code: sample[7],
            },
        });
    }
    // Empty payload means absent *in this payload*, not unsupported sensor.
    Ok(if records.is_empty() {
        Availability::Absent
    } else {
        Availability::Available(records)
    })
}
/// In-memory reference store for one source/firmware and a bounded sync window.
/// No Debug/Serialize on health-bearing types: no automatic logging/export.
pub struct Window {
    source: Source,
    samples: BTreeMap<i64, Record>,
}
impl Window {
    pub fn new(source: Source) -> Result<Self, IngestError> {
        if !valid_source(&source) {
            return Err(IngestError::Source);
        }
        Ok(Self {
            source,
            samples: BTreeMap::new(),
        })
    }
    /// Atomic batch: a conflicting value is reported, never silently overwritten.
    pub fn merge(&mut self, records: Vec<Record>) -> Result<usize, IngestError> {
        let mut staged = self.samples.clone();
        let phase = staged
            .first_key_value()
            .map(|(t, _)| t.rem_euclid(60))
            .or_else(|| records.first().map(|r| r.unix_seconds.rem_euclid(60)));
        for record in records {
            if record.source != self.source {
                return Err(IngestError::Scope);
            }
            if record.unix_seconds < 0
                || Some(record.unix_seconds.rem_euclid(60)) != phase
                || record
                    .utc_offset_seconds
                    .is_some_and(|v| !(-86400..=86400).contains(&v))
            {
                return Err(IngestError::Time);
            }
            if let Some(existing) = staged.get(&record.unix_seconds) {
                if existing != &record {
                    return Err(IngestError::Conflict);
                }
            } else {
                if staged.len() == MAX_RECORDS {
                    return Err(IngestError::Capacity);
                }
                staged.insert(record.unix_seconds, record);
            }
        }
        let added = staged.len() - self.samples.len();
        self.samples = staged;
        Ok(added)
    }
    /// Explicit expected window, end exclusive; never infer sampling for other categories.
    pub fn missing_minutes(&self, start: i64, end: i64) -> Result<Vec<i64>, IngestError> {
        let span = end.checked_sub(start).ok_or(IngestError::Time)?;
        if start < 0
            || span < 0
            || span % 60 != 0
            || span / 60 > MAX_RECORDS as i64
            || self
                .samples
                .first_key_value()
                .is_some_and(|(t, _)| t.rem_euclid(60) != start.rem_euclid(60))
        {
            return Err(IngestError::Time);
        }
        Ok((0..span / 60)
            .map(|i| start + i * 60)
            .filter(|t| !self.samples.contains_key(t))
            .collect())
    }
    /// First missing minute, not highest observed timestamp. Persist only after DB commit.
    pub fn resume_from(&self, start: i64, end: i64) -> Result<i64, IngestError> {
        Ok(self
            .missing_minutes(start, end)?
            .first()
            .copied()
            .unwrap_or(end))
    }
    pub fn len(&self) -> usize {
        self.samples.len()
    }
    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }
}

#[cfg(test)]
mod tests;
