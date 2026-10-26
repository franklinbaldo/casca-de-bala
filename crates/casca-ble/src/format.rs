//! Pure helpers shared by the Android worker, compiled and tested on every target.
use serde_json::{json, Value};

const SHOWN_BYTES: usize = 64;

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Hex plus text when the bytes are printable UTF-8 (revision strings, names).
/// Hex always; little-endian integer for 1–8 bytes (battery 0x36 is 54, not
/// the character "6"); text only for 2+ printable bytes, like revision strings.
pub(crate) fn value(bytes: &[u8]) -> Value {
    let shown = &bytes[..bytes.len().min(SHOWN_BYTES)];
    let mut v = json!({"len": bytes.len(), "hex": hex(shown)});
    if (1..=8).contains(&bytes.len()) {
        let n = bytes
            .iter()
            .rev()
            .fold(0u64, |acc, b| acc << 8 | u64::from(*b));
        v["uint_le"] = json!(n);
    }
    if let Ok(text) = std::str::from_utf8(bytes) {
        if bytes.len() >= 2 && text.chars().all(|c| !c.is_control()) {
            v["text"] = json!(text);
        }
    }
    v
}

/// Firmware-update services: a wrong write there can brick the device, so
/// they stay read-only even inside an approved write window.
pub(crate) const NO_WRITE_SERVICES: [&str; 3] = [
    "00001530-0000-3512-2118-0009af100700", // Huami/Xiaomi DFU
    "0000fe59-0000-1000-8000-00805f9b34fb", // Nordic secure DFU
    "1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0", // Silicon Labs OTA
];

/// True for firmware-update services, whatever the case of the UUID text.
pub(crate) fn write_forbidden(service: &str) -> bool {
    NO_WRITE_SERVICES.contains(&service.to_ascii_lowercase().as_str())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_bytes_are_numbers_not_text() {
        let battery = value(&[0x36]);
        assert_eq!(battery["uint_le"], 54);
        assert!(battery.get("text").is_none());
        assert_eq!(value(&[0x01, 0x02])["uint_le"], 0x0201);
    }

    #[test]
    fn revision_strings_keep_text() {
        let v = value(b"2.2.0.1");
        assert_eq!(v["text"], "2.2.0.1");
        assert_eq!(v["hex"], "322e322e302e31");
        assert!(value(&[0u8; 9]).get("uint_le").is_none());
    }

    #[test]
    fn firmware_update_services_are_never_writable() {
        assert!(write_forbidden("00001530-0000-3512-2118-0009AF100700"));
        assert!(write_forbidden("0000fe59-0000-1000-8000-00805f9b34fb"));
        assert!(!write_forbidden("00001811-0000-1000-8000-00805f9b34fb"));
    }
}
