// SPDX-License-Identifier: AGPL-3.0-or-later
// Layout reference: Gadgetbridge FetchHeartRateRestingOperation,
// FetchHeartRateMaxOperation and FetchSpo2NormalOperation; copyright
// their contributors. Standalone Rust interpretation with stricter bounds.
//! Offline decoders for decrypted history: six-byte HR and SpO2 v2.
//! No transport, health data export, or claimed Band 7 availability.

use crate::ingestion::{Availability, Source, MAX_RECORDS};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeartRateKind {
    Resting,
    Maximum,
}

#[derive(Clone, PartialEq, Eq)]
pub struct HeartRateRecord {
    pub source: Source,
    pub kind: HeartRateKind,
    /// Little-endian unsigned seconds since Unix epoch from the record.
    pub unix_seconds: i64,
    /// Raw signed number of 15-minute steps, preserved without timezone guessing.
    pub utc_offset_quarters: i8,
    /// Original byte: no assumption that 0 or 255 means absence.
    pub heart_rate_code: u8,
}

#[derive(Clone, PartialEq, Eq)]
pub struct Spo2Record {
    pub source: Source,
    /// Signed 32-bit little-endian seconds in the v2 record.
    pub unix_seconds: i64,
    /// Preserves both the high-bit flag and reported low-seven-bit value.
    pub raw_value: u8,
    pub automatic: bool,
    /// Low seven bits of raw_value; not proof of a valid physiological sample.
    pub percent_code: u8,
    /// Uninterpreted bytes must not be silently discarded or turned into samples.
    pub unknown: [u8; 60],
}

#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    Source,
    Length,
    Version,
    Time,
}

fn check_source(source: &Source) -> Result<(), DecodeError> {
    if [&source.device_ref, &source.firmware].iter().all(|value| {
        (1..=64).contains(&value.len())
            && value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
    }) {
        Ok(())
    } else {
        Err(DecodeError::Source)
    }
}

/// Historical resting and maximum heart rate are distinct fetch categories.
/// A full six-byte record: epoch u32 LE, signed quarter-hour offset, HR code.
pub fn decode_heart_rate6(
    source: Source,
    kind: HeartRateKind,
    bytes: &[u8],
) -> Result<Availability<Vec<HeartRateRecord>>, DecodeError> {
    check_source(&source)?;
    if !bytes.len().is_multiple_of(6) || bytes.len() / 6 > MAX_RECORDS {
        return Err(DecodeError::Length);
    }
    if bytes.is_empty() {
        return Ok(Availability::Absent);
    }
    let mut rows = Vec::with_capacity(bytes.len() / 6);
    for chunk in bytes.as_chunks::<6>().0 {
        rows.push(HeartRateRecord {
            source: source.clone(),
            kind,
            unix_seconds: i64::from(u32::from_le_bytes(chunk[..4].try_into().unwrap())),
            utc_offset_quarters: chunk[4] as i8,
            heart_rate_code: chunk[5],
        });
    }
    Ok(Availability::Available(rows))
}

/// Only the Gadgetbridge SPO2_NORMAL version-2 layout; not sleep SpO2.
/// One version byte followed by 65-byte records: epoch i32 LE, encoded value
/// and 60 uninterpreted bytes. Empty input is not a valid versioned message.
pub fn decode_spo2_v2(
    source: Source,
    bytes: &[u8],
) -> Result<Availability<Vec<Spo2Record>>, DecodeError> {
    check_source(&source)?;
    if bytes.is_empty()
        || !(bytes.len() - 1).is_multiple_of(65)
        || (bytes.len() - 1) / 65 > MAX_RECORDS
    {
        return Err(DecodeError::Length);
    }
    if bytes[0] != 2 {
        return Err(DecodeError::Version);
    }
    if bytes.len() == 1 {
        return Ok(Availability::Absent);
    }
    let mut rows = Vec::with_capacity((bytes.len() - 1) / 65);
    for chunk in bytes[1..].as_chunks::<65>().0 {
        let unix_seconds = i64::from(i32::from_le_bytes(chunk[..4].try_into().unwrap()));
        if unix_seconds < 0 {
            return Err(DecodeError::Time);
        }
        let raw_value = chunk[4];
        let mut unknown = [0_u8; 60];
        unknown.copy_from_slice(&chunk[5..65]);
        rows.push(Spo2Record {
            source: source.clone(),
            unix_seconds,
            raw_value,
            automatic: (raw_value & 0x80) != 0,
            percent_code: raw_value & 0x7f,
            unknown,
        });
    }
    Ok(Availability::Available(rows))
}
