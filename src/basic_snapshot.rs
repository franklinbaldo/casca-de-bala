//! Local cached basic-read snapshot. Caller-asserted origin, never physical attestation.
use crate::gatt_basics::{decode, Kind, Pnp, Reader};
use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Read,
    NotPresent,
    NotRead,
    Invalid,
    Denied,
    GattError,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Field<T> {
    pub state: State,
    pub value: Option<T>,
}
impl<T> Field<T> {
    fn valid(&self) -> bool {
        (self.state == State::Read) == self.value.is_some()
    }
    fn count(&self) -> usize {
        usize::from(self.state == State::Read)
    }
}
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Complete,
    Partial,
    Empty,
    Failed,
    Interrupted,
}
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Cause {
    None,
    Permission,
    BluetoothOff,
    Unavailable,
    Connection,
    Discovery,
    Timeout,
    Gatt,
    InvalidReply,
    Native,
    Cancelled,
    Background,
    ServiceChanged,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Fields {
    pub battery: Field<u8>,
    pub hardware: Field<String>,
    pub software: Field<String>,
    pub pnp: Field<Pnp>,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub reader: Reader,
    pub stage: Stage,
    pub cause: Cause,
    pub read_at_unix_ms: Option<i64>,
    pub fields: Fields,
}
impl Snapshot {
    pub fn validate(&self) -> Result<usize, &'static str> {
        let f = &self.fields;
        if !f.battery.valid() || !f.hardware.valid() || !f.software.valid() || !f.pnp.valid() {
            return Err("invalid_snapshot");
        }
        if let Some(b) = f.battery.value {
            decode(Kind::Battery, &[b]).map_err(|_| "invalid_snapshot")?;
        }
        for (kind, field) in [
            (Kind::HardwareRevision, &f.hardware),
            (Kind::SoftwareRevision, &f.software),
        ] {
            if let Some(value) = &field.value {
                decode(kind, value.as_bytes()).map_err(|_| "invalid_snapshot")?;
            }
        }
        if let Some(p) = &f.pnp.value {
            let mut bytes = vec![p.vendor_source];
            bytes.extend(p.vendor_id.to_le_bytes());
            bytes.extend(p.product_id.to_le_bytes());
            bytes.extend(p.product_version_bcd.to_le_bytes());
            decode(Kind::PnpId, &bytes).map_err(|_| "invalid_snapshot")?;
        }
        let count = f.battery.count() + f.hardware.count() + f.software.count() + f.pnp.count();
        if (count > 0) != self.read_at_unix_ms.is_some()
            || self.read_at_unix_ms.is_some_and(|t| t < 0)
        {
            return Err("invalid_snapshot");
        }
        let expected = if count == 4 && self.cause == Cause::None {
            Stage::Complete
        } else if count > 0 {
            Stage::Partial
        } else if self.cause == Cause::None {
            Stage::Empty
        } else if matches!(self.cause, Cause::Cancelled | Cause::Background) {
            Stage::Interrupted
        } else {
            Stage::Failed
        };
        if self.stage != expected {
            return Err("invalid_snapshot");
        }
        Ok(count)
    }
}
