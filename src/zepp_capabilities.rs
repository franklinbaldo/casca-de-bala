// SPDX-License-Identifier: AGPL-3.0-or-later
//! Offline request preparation/decoding, not a transport or permission grant.
//! Wire reference: Gadgetbridge a0948ee1cbc2a870f91d313f8e37df5f524465f7,
//! Huami2021Service, ZeppOsSupport, HuamiBatteryInfo, ZeppOsNotificationService.
use serde::Serialize;
use std::collections::BTreeMap;
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Operation {
    BatteryRead,
    FindStart,
    FindStop,
    NotificationCapabilities,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Effect {
    ReadOnly,
    HapticOutput,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Cipher {
    Plaintext,
    Encrypted,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    Length,
    Opcode,
    Range,
    Limit,
    UnsupportedVersion(u8),
    DiscoveryRequired,
    ServiceAbsent,
    UnknownEncryption,
    EncryptedFramingRequired,
    Framing,
}
/// Already-decoded service list: absent key != unknown flag. A synthetic map does
/// not establish physical support or authentication. Bootstrap parser is separate.
pub type ServiceEncryption = BTreeMap<u16, Option<bool>>;
#[derive(Debug, Clone, Serialize)]
pub struct Prepared {
    operation: Operation,
    endpoint: u16,
    payload: [u8; 1],
    effect: Effect,
    offline_only: bool,
    executable: bool,
    authentication_required: bool,
    user_write_approval_required: bool,
    physical_support_verified: bool,
    cipher_policy: &'static str,
}
pub fn prepare(operation: Operation) -> Prepared {
    let (endpoint, byte, effect) = match operation {
        Operation::BatteryRead => (0x29, 3, Effect::ReadOnly),
        Operation::FindStart => (0x1a, 3, Effect::HapticOutput),
        Operation::FindStop => (0x1a, 6, Effect::HapticOutput),
        Operation::NotificationCapabilities => (0x1e, 1, Effect::ReadOnly),
    };
    Prepared {
        operation,
        endpoint,
        payload: [byte],
        effect,
        offline_only: true,
        executable: false,
        authentication_required: true,
        user_write_approval_required: effect == Effect::HapticOutput,
        physical_support_verified: false,
        cipher_policy: "resolve_from_service_discovery",
    }
}
impl Prepared {
    pub fn endpoint(&self) -> u16 {
        self.endpoint
    }
    pub fn payload(&self) -> &[u8] {
        &self.payload
    }
    pub fn effect(&self) -> Effect {
        self.effect
    }
    pub fn executable(&self) -> bool {
        false
    }
    /// Per-unit reported flag, not the ignored boolean at upstream call sites.
    /// This is offline metadata resolution, never an authenticated-session proof.
    pub fn resolve_cipher(&self, services: Option<&ServiceEncryption>) -> Result<Cipher, Error> {
        match services
            .ok_or(Error::DiscoveryRequired)?
            .get(&self.endpoint)
        {
            None => Err(Error::ServiceAbsent),
            Some(None) => Err(Error::UnknownEncryption),
            Some(Some(false)) => Ok(Cipher::Plaintext),
            Some(Some(true)) => Ok(Cipher::Encrypted),
        }
    }
    /// Explicit simulation only. Encrypted endpoints cannot fall back to plain.
    /// Real encrypted framing/key custody remains in the Java session owner.
    pub fn frame_plain_for_simulation(
        &self,
        handle: u8,
        mtu: usize,
        services: Option<&ServiceEncryption>,
    ) -> Result<Vec<Vec<u8>>, Error> {
        if self.resolve_cipher(services)? != Cipher::Plaintext {
            return Err(Error::EncryptedFramingRequired);
        }
        crate::huami_chunk::encode_plain(self.endpoint, handle, mtu, &self.payload)
            .map_err(|_| Error::Framing)
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub enum Charging {
    Normal,
    Charging,
    Unknown(u8),
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub enum ReportedPercent {
    Known(u8),
    Unknown(u8),
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct BatteryReply {
    pub percent: u8,
    pub charging: Charging,
    pub last_charge_percent: ReportedPercent,
    /// Raw device clock bytes, deliberately NOT interpreted as UTC/Unix time.
    pub last_charge_device_time_raw: [u8; 8],
    pub raw_payload: [u8; 21],
}
pub fn decode_battery(p: &[u8]) -> Result<BatteryReply, Error> {
    if p.len() != 21 {
        return Err(Error::Length);
    }
    if p[0] != 4 {
        return Err(Error::Opcode);
    }
    if p[2] > 100 {
        return Err(Error::Range);
    }
    Ok(BatteryReply {
        percent: p[2],
        charging: match p[3] {
            0 => Charging::Normal,
            1 => Charging::Charging,
            n => Charging::Unknown(n),
        },
        last_charge_percent: if p[20] <= 100 {
            ReportedPercent::Known(p[20])
        } else {
            ReportedPercent::Unknown(p[20])
        },
        last_charge_device_time_raw: p[11..19].try_into().unwrap(),
        raw_payload: p.try_into().unwrap(),
    })
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub enum FindEvent {
    /// Receipt only, NOT evidence that vibration/motor output happened.
    Acknowledged {
        uninterpreted: Vec<u8>,
    },
    StoppedByBand {
        uninterpreted: Vec<u8>,
    },
}
pub fn decode_find_event(p: &[u8]) -> Result<FindEvent, Error> {
    if p.is_empty() {
        return Err(Error::Length);
    }
    if p.len() > 32 {
        return Err(Error::Limit);
    }
    match p[0] {
        4 => Ok(FindEvent::Acknowledged {
            uninterpreted: p[1..].to_vec(),
        }),
        7 => Ok(FindEvent::StoppedByBand {
            uninterpreted: p[1..].to_vec(),
        }),
        _ => Err(Error::Opcode),
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NotificationCapabilities {
    /// Notification service protocol version, NOT the ZeppOS API level.
    pub version: u8,
    pub uninterpreted_word: u16,
    pub uninterpreted_flags: [u8; 2],
    pub opaque_descriptors: Vec<u8>,
    pub supports_pictures: Option<bool>,
    pub supports_notification_key: Option<bool>,
}
pub fn decode_notification_capabilities(p: &[u8]) -> Result<NotificationCapabilities, Error> {
    if p.len() < 2 {
        return Err(Error::Length);
    }
    if p[0] != 2 {
        return Err(Error::Opcode);
    }
    let version = p[1];
    if version != 4 && version != 5 {
        return Err(Error::UnsupportedVersion(version));
    }
    if p.len() < 8 {
        return Err(Error::Length);
    }
    let n = u16::from_le_bytes([p[6], p[7]]) as usize;
    if n > 256 {
        return Err(Error::Limit);
    }
    let end = 8 + n;
    let extra = if version == 5 { 2 } else { 0 };
    if p.len() != end + extra {
        return Err(Error::Length);
    }
    Ok(NotificationCapabilities {
        version,
        uninterpreted_word: u16::from_le_bytes([p[2], p[3]]),
        uninterpreted_flags: [p[4], p[5]],
        opaque_descriptors: p[8..end].to_vec(),
        supports_pictures: if version == 5 {
            Some(p[end] != 0)
        } else {
            None
        },
        supports_notification_key: if version == 5 {
            Some(p[end + 1] != 0)
        } else {
            None
        },
    })
}
