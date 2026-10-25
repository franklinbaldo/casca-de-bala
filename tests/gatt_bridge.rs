use band7_terminal::gatt_bridge::{b7_gatt_json, process, MAX_INPUT, MAX_OUTPUT};
use serde_json::{json, Value};
fn call(value: Value) -> Value {
    serde_json::from_slice(&process(&serde_json::to_vec(&value).unwrap())).unwrap()
}
#[test]
fn envelopes_preserve_ids_and_reuse_read_semantics() {
    let result = call(
        json!({"version":1,"id":u64::MAX,"command":{"op":"interpret",
        "kind":"battery","reader":"synthetic","outcome":{"state":"gatt","status":0,"bytes":[91]}}}),
    );
    assert_eq!(result["id"], u64::MAX);
    assert_eq!(
        result["result"]["read"]["read"]["value"]["battery_percent"],
        91
    );
    assert_eq!(result["result"]["read"]["read"]["reader"], "synthetic");
    let denied = call(json!({"version":1,"id":2,"command":{"op":"interpret",
        "kind":"pnp_id","reader":"synthetic","outcome":{"state":"permission_denied"}}}));
    assert_eq!(denied["result"]["read"], "permission_denied");
    let bad = call(json!({"version":1,"id":3,"command":{"op":"interpret",
        "kind":"battery","reader":"synthetic","outcome":{"state":"gatt","status":0,"bytes":[255]}}}));
    assert_eq!(bad["result"]["read"]["invalid_value"], "percent");
    let failed = call(json!({"version":1,"id":4,"command":{"op":"interpret",
        "kind":"battery","reader":"synthetic","outcome":{"state":"gatt","status":5,"bytes":[91]}}}));
    assert_eq!(failed["result"]["read"]["gatt_failure"], 5);
}
#[test]
fn plan_and_strict_request_limits() {
    let row = json!({"service":{"bluetooth16":6159},"characteristic":{"bluetooth16":10777},"readable":true});
    let request = |rows: Vec<Value>| json!({"version":1,"id":7,"command":{"op":"plan","connect_permission":true,"discovery":rows}});
    assert_eq!(
        call(request(vec![row.clone()]))["result"]["plan"][0]["read"],
        "battery"
    );
    assert_eq!(call(request(vec![row; 129]))["error"], "discovery_limit");
    for invalid in [
        json!({"version":1,"id":1,"command":{"op":"plan","connect_permission":true,"discovery":[],"extra":0}}),
        json!({"version":1,"id":1,"extra":0,"command":{"op":"plan","connect_permission":true,"discovery":[]}}),
        json!({"version":1,"id":1,"command":{"op":"interpret","kind":"battery","reader":"synthetic","outcome":{"state":"not_attempted","bytes":[]}}}),
        json!({"version":1,"id":1,"command":{"op":"interpret","kind":"battery","reader":"synthetic","outcome":{"state":"gatt","status":0,"bytes":[256]}}}),
    ] {
        assert_eq!(call(invalid)["error"], "invalid_input");
    }
    assert_eq!(
        call(
            json!({"version":2,"id":19,"command":{"op":"plan","connect_permission":true,"discovery":[]}})
        ),
        json!({"version":1,"id":19,"error":"unsupported_version"})
    );
    for input in [
        vec![],
        vec![255],
        vec![b' '; MAX_INPUT + 1],
        br#"{"version":1,"version":1}"#.to_vec(),
    ] {
        let r: Value = serde_json::from_slice(&process(&input)).unwrap();
        assert_eq!(r["error"], "invalid_input");
        assert!(r["id"].is_null());
    }
}
#[test]
fn abi_has_owned_buffers_and_no_partial_output_on_failure() {
    let input=br#"{"version":1,"id":42,"command":{"op":"interpret","kind":"software_revision","reader":"synthetic","outcome":{"state":"gatt","status":0,"bytes":[195,167]}}}"#;
    let mut output = vec![0xa5; MAX_OUTPUT];
    let mut written = 999;
    unsafe {
        assert_eq!(
            b7_gatt_json(
                input.as_ptr(),
                input.len(),
                output.as_mut_ptr(),
                1,
                &mut written
            ),
            8
        );
        assert_eq!(written, 0);
        assert!(output.iter().all(|b| *b == 0xa5));
        assert_eq!(
            b7_gatt_json(
                std::ptr::null(),
                1,
                output.as_mut_ptr(),
                MAX_OUTPUT,
                &mut written
            ),
            1
        );
        assert_eq!(written, 0);
        assert_eq!(
            b7_gatt_json(
                input.as_ptr(),
                MAX_INPUT + 1,
                output.as_mut_ptr(),
                MAX_OUTPUT,
                &mut written
            ),
            1
        );
        assert_eq!(
            b7_gatt_json(
                input.as_ptr(),
                input.len(),
                output.as_mut_ptr(),
                MAX_OUTPUT,
                std::ptr::null_mut()
            ),
            1
        );
        assert_eq!(
            b7_gatt_json(
                input.as_ptr(),
                input.len(),
                output.as_mut_ptr(),
                MAX_OUTPUT,
                &mut written
            ),
            0
        );
    }
    let value: Value = serde_json::from_slice(&output[..written]).unwrap();
    assert_eq!(value["id"], 42);
    assert_eq!(value["result"]["read"]["read"]["value"]["revision"], "ç");
    // Result is copied; a second call cannot overwrite the previous caller buffer.
    let before = output.clone();
    assert!(!process(b"invalid").is_empty());
    assert_eq!(output, before);
}
