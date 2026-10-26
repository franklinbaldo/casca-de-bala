use band7_terminal::{b7_call, B7Result};
use serde_json::{json, Value};
use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};
fn call(value: Value) -> B7Result {
    let bytes = serde_json::to_vec(&value).unwrap();
    let mut result = B7Result::default();
    let status = unsafe { b7_call(bytes.as_ptr(), bytes.len(), &mut result) };
    assert_eq!(status, result.status);
    result
}
fn request(command: Value) -> B7Result {
    call(json!({"version":1,"command":command}))
}
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "b7-ffi-fixture-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
    fn open(&self, path: PathBuf) -> B7Result {
        request(
            json!({"op":"open","path":path,"device_ref":"fixture","firmware":"test","synthetic":true,"start":120,"end":240}),
        )
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
#[test]
fn error_boundaries_zero_output_and_never_open_on_invalid_output() {
    let mut result = B7Result {
        handle: 123,
        ..B7Result::default()
    };
    unsafe {
        assert_eq!(b7_call(std::ptr::null(), 1, &mut result), 1);
        assert_eq!(b7_call(b"x".as_ptr(), 70001, &mut result), 1);
        assert_eq!(b7_call(b"x".as_ptr(), 1, std::ptr::null_mut()), 1);
    }
    assert_eq!(result.handle, 0);
    for value in [
        json!({}),
        json!({"version":2,"command":{"op":"close","handle":1}}),
        json!({"version":1,"command":{"op":"coverage","handle":1,"extra":1}}),
    ] {
        assert_eq!(call(value).status, 1);
    }
    let bad = request(json!({"op":"ingest","handle":999,"first":120,"activity8_hex":"GG"}));
    assert_eq!(bad.status, 1);
}
#[test]
fn concurrent_replay_then_close_reopen_and_database_open_error() {
    let temp = Temp::new();
    assert_eq!(temp.open(temp.0.clone()).status, 3);
    let file = temp.0.join("synthetic.sqlite");
    let open = temp.open(file.clone());
    assert_eq!(open.status, 0);
    let handle = open.handle;
    let workers:Vec<_>=(0..8).map(|_|std::thread::spawn(move||{let r=request(json!({"op":"ingest","handle":handle,"first":120,"utc_offset_seconds":0,"activity8_hex":"010203ff05060708"}));assert_eq!(r.status,0);r.inserted})).collect();
    assert_eq!(
        workers.into_iter().map(|t| t.join().unwrap()).sum::<u32>(),
        1
    );
    let before = request(json!({"op":"coverage","handle":handle}));
    assert_eq!((before.records, before.missing, before.cursor), (1, 1, 180));
    let conflict = request(
        json!({"op":"ingest","handle":handle,"first":120,"utc_offset_seconds":0,"activity8_hex":"020203ff05060708"}),
    );
    assert_eq!(conflict.status, 4);
    assert_eq!(request(json!({"op":"close","handle":handle})).status, 0);
    assert_eq!(request(json!({"op":"coverage","handle":handle})).status, 2);
    let reopen = temp.open(file);
    assert_eq!(reopen.status, 0);
    assert_ne!(reopen.handle, handle);
    let after = request(json!({"op":"coverage","handle":reopen.handle}));
    assert_eq!((after.records, after.missing, after.cursor), (1, 1, 180));
    assert_eq!(
        request(json!({"op":"close","handle":reopen.handle})).status,
        0
    );
    assert_eq!(
        request(json!({"op":"close","handle":reopen.handle})).status,
        2
    );
}
