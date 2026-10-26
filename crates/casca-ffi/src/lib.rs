// SPDX-License-Identifier: AGPL-3.0-or-later
//! Synchronous host-tested ABI. No JNI/Android runtime or Bluetooth IO.
use casca_core::ingestion::Source;
use casca_store::activity_db::{ActivityDb, StoreError};
use serde::Deserialize;
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    sync::{Mutex, OnceLock},
};
const MAX_INPUT: usize = 70000;
#[repr(C)]
#[derive(Default, Clone, Copy)]
pub struct B7Result {
    pub status: i32,
    pub has_cursor: u32,
    pub handle: u64,
    pub cursor: i64,
    pub inserted: u32,
    pub records: u32,
    pub missing: u32,
    pub reserved: u32,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    version: u8,
    command: Operation,
}
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Operation {
    Open {
        path: String,
        device_ref: String,
        firmware: String,
        synthetic: bool,
        start: i64,
        end: i64,
    },
    Ingest {
        handle: u64,
        first: i64,
        utc_offset_seconds: Option<i32>,
        activity8_hex: String,
    },
    Coverage {
        handle: u64,
    },
    Close {
        handle: u64,
    },
}
struct State {
    next: u64,
    stores: HashMap<u64, ActivityDb>,
}
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
fn status(error: StoreError) -> i32 {
    match error {
        StoreError::Ingestion(_) => 4,
        _ => 3,
    }
}
fn execute(input: &[u8]) -> Result<B7Result, i32> {
    let request: Request = serde_json::from_slice(input).map_err(|_| 1)?;
    if request.version != 1 {
        return Err(1);
    }
    let mut state = STATE
        .get_or_init(|| {
            Mutex::new(State {
                next: 1,
                stores: HashMap::new(),
            })
        })
        .lock()
        .map_err(|_| 5)?;
    let mut output = B7Result::default();
    match request.command {
        Operation::Open {
            path,
            device_ref,
            firmware,
            synthetic,
            start,
            end,
        } => {
            if path.len() > 4096 || path.contains('\0') || state.stores.len() >= 32 {
                return Err(1);
            }
            let handle = state.next;
            let next = handle.checked_add(1).ok_or(1)?;
            let store = ActivityDb::open(
                Path::new(&path),
                Source {
                    device_ref,
                    firmware,
                    synthetic,
                },
                start,
                end,
            )
            .map_err(status)?;
            state.stores.insert(handle, store);
            state.next = next;
            output.handle = handle;
        }
        Operation::Ingest {
            handle,
            first,
            utc_offset_seconds,
            activity8_hex,
        } => {
            if activity8_hex.len() > 65536 || !activity8_hex.len().is_multiple_of(2) {
                return Err(1);
            }
            let mut bytes = Vec::with_capacity(activity8_hex.len() / 2);
            for pair in activity8_hex.as_bytes().as_chunks::<2>().0 {
                let digit = |b: u8| match b {
                    b'0'..=b'9' => Ok(b - b'0'),
                    b'a'..=b'f' => Ok(b - b'a' + 10),
                    _ => Err(1),
                };
                bytes.push(digit(pair[0])? * 16 + digit(pair[1])?);
            }
            let store = state.stores.get_mut(&handle).ok_or(2)?;
            let committed = store
                .ingest(first, utc_offset_seconds, &bytes)
                .map_err(status)?;
            // No fallible query after a successful commit: status reflects that commit.
            output.handle = handle;
            output.inserted = committed.inserted as u32;
            output.cursor = committed.resume_from;
            output.has_cursor = 1;
        }
        Operation::Coverage { handle } => {
            let store = state.stores.get_mut(&handle).ok_or(2)?;
            let (cursor, records, missing) = store.coverage_snapshot().map_err(status)?;
            output.handle = handle;
            output.has_cursor = u32::from(cursor.is_some());
            output.cursor = cursor.unwrap_or(0);
            output.records = records as u32;
            output.missing = missing as u32;
        }
        Operation::Close { handle } => {
            state.stores.remove(&handle).ok_or(2)?;
            output.handle = handle;
        }
    }
    Ok(output)
}
/// # Safety
/// Input points to `length` readable bytes; output points to one writable,
/// aligned B7Result. Buffers must be valid for the call and must not overlap.
/// No pointers are retained. Null/length limits are checked, not memory validity.
#[no_mangle]
pub unsafe extern "C" fn b7_call(input: *const u8, length: usize, output: *mut B7Result) -> i32 {
    if output.is_null() {
        return 1;
    }
    let result = if input.is_null() || length == 0 || length > MAX_INPUT {
        Err(1)
    } else {
        catch_unwind(AssertUnwindSafe(|| {
            execute(unsafe { std::slice::from_raw_parts(input, length) })
        }))
        .unwrap_or(Err(6))
    };
    let value = match result {
        Ok(value) => value,
        Err(status) => B7Result {
            status,
            ..B7Result::default()
        },
    };
    unsafe {
        output.write(value);
    }
    value.status
}
