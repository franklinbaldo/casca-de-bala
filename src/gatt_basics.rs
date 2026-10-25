//! Offline DIS/PnP/Battery parsing and a read-only capability plan.
//! No GATT calls, writes, notifications, Huami authentication, or deviceSource inference.
use crate::ingestion::Availability;
use serde::{Deserialize, Serialize};
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    HardwareRevision,
    SoftwareRevision,
    PnpId,
    Battery,
}
impl Kind {
    pub fn key(self) -> (u16, u16) {
        match self {
            Self::HardwareRevision => (0x180a, 0x2a27),
            Self::SoftwareRevision => (0x180a, 0x2a28),
            Self::PnpId => (0x180a, 0x2a50),
            Self::Battery => (0x180f, 0x2a19),
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Uuid {
    Bluetooth16(u16),
    Vendor128([u8; 16]),
}
#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Discovered {
    pub service: Uuid,
    pub characteristic: Uuid,
    pub readable: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Skip {
    ConnectPermission,
    NotReadable,
    PrivilegedHid,
    SensitiveIdentifier,
    OutsideBasicScope,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Decision {
    Read(Kind),
    Skip(Skip),
}
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Error {
    Length,
    Utf8,
    Revision,
    VendorSource,
    Bcd,
    Percent,
    DiscoveryLimit,
}
/// Only canonical Bluetooth-base UUIDs may become Bluetooth16 in the platform adapter.
/// Vendor UUID suffixes are never shortened into SIG assigned numbers.
pub fn plan(discovery: &[Discovered], connect_permission: bool) -> Result<Vec<Decision>, Error> {
    if discovery.len() > 128 {
        return Err(Error::DiscoveryLimit);
    }
    Ok(discovery
        .iter()
        .map(|d| {
            if d.service == Uuid::Bluetooth16(0x1812)
                || matches!(d.characteristic, Uuid::Bluetooth16(0x2a4a..=0x2a4d))
            {
                return Decision::Skip(Skip::PrivilegedHid);
            }
            if matches!(d.characteristic, Uuid::Bluetooth16(0x2a23 | 0x2a25)) {
                return Decision::Skip(Skip::SensitiveIdentifier);
            }
            let kind = [
                Kind::HardwareRevision,
                Kind::SoftwareRevision,
                Kind::PnpId,
                Kind::Battery,
            ]
            .into_iter()
            .find(|k| {
                let (s, c) = k.key();
                d.service == Uuid::Bluetooth16(s) && d.characteristic == Uuid::Bluetooth16(c)
            });
            let Some(kind) = kind else {
                return Decision::Skip(Skip::OutsideBasicScope);
            };
            if !connect_permission {
                return Decision::Skip(Skip::ConnectPermission);
            }
            if !d.readable {
                return Decision::Skip(Skip::NotReadable);
            }
            Decision::Read(kind)
        })
        .collect())
}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct Pnp {
    pub vendor_source: u8,
    pub vendor_id: u16,
    pub product_id: u16,
    pub product_version_bcd: u16,
}
impl Pnp {
    pub fn version(&self) -> (u8, u8, u8) {
        let b = self.product_version_bcd;
        (
            (((b >> 12) & 15) * 10 + ((b >> 8) & 15)) as u8,
            ((b >> 4) & 15) as u8,
            (b & 15) as u8,
        )
    }
}
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Value {
    Revision(String),
    Pnp(Pnp),
    BatteryPercent(u8),
}
pub fn decode(kind: Kind, bytes: &[u8]) -> Result<Value, Error> {
    match kind {
        Kind::HardwareRevision | Kind::SoftwareRevision => {
            if bytes.is_empty() || bytes.len() > 64 {
                return Err(Error::Length);
            }
            let text = std::str::from_utf8(bytes).map_err(|_| Error::Utf8)?;
            if text.trim().is_empty() || text.chars().any(char::is_control) {
                return Err(Error::Revision);
            }
            Ok(Value::Revision(text.into()))
        }
        Kind::Battery => {
            if bytes.len() != 1 {
                return Err(Error::Length);
            }
            if bytes[0] > 100 {
                return Err(Error::Percent);
            }
            Ok(Value::BatteryPercent(bytes[0]))
        }
        Kind::PnpId => {
            if bytes.len() != 7 {
                return Err(Error::Length);
            }
            if !matches!(bytes[0], 1 | 2) {
                return Err(Error::VendorSource);
            }
            let bcd = u16::from_le_bytes([bytes[5], bytes[6]]);
            if (0..4).any(|i| ((bcd >> (i * 4)) & 15) > 9) {
                return Err(Error::Bcd);
            }
            Ok(Value::Pnp(Pnp {
                vendor_source: bytes[0],
                vendor_id: u16::from_le_bytes([bytes[1], bytes[2]]),
                product_id: u16::from_le_bytes([bytes[3], bytes[4]]),
                product_version_bcd: bcd,
            }))
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Reader {
    Synthetic,
    UserNrfConnect,
    OurApp,
}
pub enum ReadOutcome<'a> {
    NotAttempted,
    PermissionDenied,
    Gatt { status: u32, bytes: &'a [u8] },
}
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadState {
    NotRead,
    PermissionDenied,
    GattFailure(u32),
    InvalidValue(Error),
    Read {
        reader: Reader,
        kind: Kind,
        value: Value,
    },
}
/// Discovery and read permission alone never become Available measurements.
pub fn interpret(kind: Kind, reader: Reader, outcome: ReadOutcome<'_>) -> ReadState {
    match outcome {
        ReadOutcome::NotAttempted => ReadState::NotRead,
        ReadOutcome::PermissionDenied => ReadState::PermissionDenied,
        ReadOutcome::Gatt { status, bytes } => {
            if status != 0 {
                return ReadState::GattFailure(status);
            }
            match decode(kind, bytes) {
                Ok(value) => ReadState::Read {
                    reader,
                    kind,
                    value,
                },
                Err(e) => ReadState::InvalidValue(e),
            }
        }
    }
}
/// No translation exists between DIS Product ID and Huami/Zepp target namespace.
pub fn device_source(_pnp: &Pnp) -> Availability<u16> {
    Availability::Unknown
}
