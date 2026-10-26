//! Bounded, stateless JSON boundary for the offline basic GATT core.
//! No Bluetooth, authentication, database, or shared handles are accessed.
use crate::gatt_basics::{self as gatt, Discovered, Kind, ReadOutcome, Reader};
use serde::Deserialize;
use serde_json::{json, Value};

pub const MAX_INPUT: usize = 32768;
pub const MAX_OUTPUT: usize = 32768;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    version: u8,
    id: u64,
    command: Command,
}
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Snapshot {
        snapshot: crate::basic_snapshot::Snapshot,
    },
    Plan {
        connect_permission: bool,
        discovery: Vec<Discovered>,
    },
    Interpret {
        kind: Kind,
        reader: Reader,
        outcome: Outcome,
    },
}
#[derive(Deserialize)]
#[serde(tag = "state", rename_all = "snake_case", deny_unknown_fields)]
enum Outcome {
    NotAttempted {},
    PermissionDenied {},
    Gatt { status: u32, bytes: Vec<u8> },
}

/// A reader is caller-asserted provenance, not attestation of a physical read.
/// Invalid envelopes have id:null; validated envelopes retain their id on errors.
pub fn process(input: &[u8]) -> Vec<u8> {
    let error = |id: Option<u64>, code: Value| json!({"version":1,"id":id,"error":code});
    let request = if input.is_empty() || input.len() > MAX_INPUT {
        None
    } else {
        serde_json::from_slice::<Request>(input).ok()
    };
    let response = match request {
        None => error(None, json!("invalid_input")),
        Some(r) if r.version != 1 => error(Some(r.id), json!("unsupported_version")),
        Some(r) => match r.command {
            Command::Snapshot { snapshot } => match snapshot.validate() {
                Ok(count) => {
                    json!({"version":1,"id":r.id,"result":{"snapshot":snapshot,"delivery":"cached_snapshot","caller_asserted_origin":true,"received_count":count}})
                }
                Err(code) => error(Some(r.id), json!(code)),
            },
            Command::Plan {
                connect_permission,
                discovery,
            } => match gatt::plan(&discovery, connect_permission) {
                Ok(decisions) => json!({"version":1,"id":r.id,"result":{"plan":decisions}}),
                Err(e) => error(Some(r.id), json!(e)),
            },
            Command::Interpret {
                kind,
                reader,
                outcome,
            } => {
                let state = match outcome {
                    Outcome::NotAttempted {} => {
                        gatt::interpret(kind, reader, ReadOutcome::NotAttempted)
                    }
                    Outcome::PermissionDenied {} => {
                        gatt::interpret(kind, reader, ReadOutcome::PermissionDenied)
                    }
                    Outcome::Gatt { status, bytes } => gatt::interpret(
                        kind,
                        reader,
                        ReadOutcome::Gatt {
                            status,
                            bytes: &bytes,
                        },
                    ),
                };
                json!({"version":1,"id":r.id,"result":{"read":state}})
            }
        },
    };
    // Fixed-size error response cannot fail serialization or exceed the bound.
    let bytes = serde_json::to_vec(&response).expect("JSON values serialize");
    if bytes.len() > MAX_OUTPUT {
        return br#"{"version":1,"id":null,"error":"output_limit"}"#.to_vec();
    }
    bytes
}

/// # Safety
/// Nonnull buffers point to valid, nonoverlapping memory for their declared sizes;
/// written points to an aligned writable usize. Caller owns all memory. No retention.
/// Return 0: JSON copied, 1: invalid pointer/length, 8: output capacity insufficient,
/// 6: caught panic. On errors written=0 and output is untouched. No state changes.
#[no_mangle]
pub unsafe extern "C" fn b7_gatt_json(
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> i32 {
    if written.is_null() {
        return 1;
    }
    unsafe { written.write(0) };
    if input.is_null() || output.is_null() || length == 0 || length > MAX_INPUT {
        return 1;
    }
    let result =
        std::panic::catch_unwind(|| process(unsafe { std::slice::from_raw_parts(input, length) }));
    let Ok(bytes) = result else { return 6 };
    if capacity < bytes.len() {
        return 8;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len());
        written.write(bytes.len());
    }
    0
}
