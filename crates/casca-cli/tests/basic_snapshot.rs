use casca_core::gatt_bridge::process;
use serde_json::{json, Value};
use std::io::Write;
use std::process::{Command, Stdio};
fn fixture() -> Value {
    serde_json::from_str(include_str!(
        "../../../fixtures/basic-snapshot-synthetic.json"
    ))
    .unwrap()
}
fn call(v: &Value) -> Value {
    serde_json::from_slice(&process(&serde_json::to_vec(v).unwrap())).unwrap()
}
#[test]
fn snapshot_contract_preserves_partial_errors_time_and_zero_without_claiming_live_io() {
    let input = fixture();
    let result = call(&input);
    assert_eq!(result["id"], 73);
    assert_eq!(result["result"]["snapshot"], input["command"]["snapshot"]);
    assert_eq!(result["result"]["delivery"], "cached_snapshot");
    assert_eq!(result["result"]["caller_asserted_origin"], true);
    assert_eq!(result["result"]["received_count"], 2);
    let mut physical = input.clone();
    physical["command"]["snapshot"]["reader"] = json!("our_app");
    assert_eq!(call(&physical)["result"]["snapshot"]["reader"], "our_app");
}
#[test]
fn snapshot_rejects_inconsistent_values_states_stages_and_times() {
    for (path, value) in [
        ("/command/snapshot/fields/battery/value", json!(101)),
        ("/command/snapshot/fields/battery/value", Value::Null),
        (
            "/command/snapshot/fields/pnp/value",
            json!({"vendor_source":1,"vendor_id":1,"product_id":2,"product_version_bcd":256}),
        ),
        ("/command/snapshot/fields/hardware/value", json!("bad\n")),
        (
            "/command/snapshot/fields/hardware/value",
            json!("x".repeat(65)),
        ),
        ("/command/snapshot/read_at_unix_ms", json!(-1)),
        ("/command/snapshot/read_at_unix_ms", Value::Null),
        ("/command/snapshot/stage", json!("complete")),
        ("/command/snapshot/stage", json!("reading")),
        ("/command/snapshot/reader", json!("verified_physical")),
    ] {
        let mut input = fixture();
        *input.pointer_mut(path).unwrap() = value;
        assert!(call(&input).get("error").is_some(), "{path}");
    }
    let mut input = fixture();
    input["command"]["snapshot"]["mac"] = json!("not-allowed");
    assert!(call(&input).get("error").is_some());
}
#[test]
fn terminal_replays_same_envelope_through_same_rust_boundary() {
    let input = fixture();
    let mut child = Command::new(env!("CARGO_BIN_EXE_band7-terminal"))
        .arg("--gatt-json")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(&serde_json::to_vec(&input).unwrap())
        .unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    assert_eq!(
        serde_json::from_slice::<Value>(&output.stdout).unwrap(),
        call(&input)
    );
}
#[test]
fn all_terminal_outcomes_and_pnp_are_checked_without_filling_missing_data() {
    let mut input = fixture();
    let fields = &mut input["command"]["snapshot"]["fields"];
    fields["software"] = json!({"state":"read","value":"lab-sw"});
    fields["pnp"] = json!({"state":"read","value":{"vendor_source":2,"vendor_id":11,"product_id":22,"product_version_bcd":258}});
    input["command"]["snapshot"]["stage"] = json!("complete");
    input["command"]["snapshot"]["cause"] = json!("none");
    assert_eq!(call(&input)["result"]["received_count"], 4);
    for bad in [json!(0), json!(3), json!(256)] {
        let mut invalid = input.clone();
        invalid["command"]["snapshot"]["fields"]["pnp"]["value"]["vendor_source"] = bad;
        assert!(call(&invalid).get("error").is_some());
    }
    input["command"]["snapshot"]["fields"]["pnp"]["value"]["product_version_bcd"] = json!(0xabcd);
    assert_eq!(call(&input)["error"], "invalid_snapshot");
    for (cause, stage) in [
        ("none", "empty"),
        ("background", "interrupted"),
        ("cancelled", "interrupted"),
        ("permission", "failed"),
    ] {
        let mut input = fixture();
        input["command"]["snapshot"]["read_at_unix_ms"] = Value::Null;
        input["command"]["snapshot"]["cause"] = json!(cause);
        input["command"]["snapshot"]["stage"] = json!(stage);
        for field in ["hardware", "software", "pnp", "battery"] {
            input["command"]["snapshot"]["fields"][field] =
                json!({"state":"not_read","value":null});
        }
        assert_eq!(call(&input)["result"]["received_count"], 0);
        input["command"]["snapshot"]["read_at_unix_ms"] = json!(1);
        assert!(call(&input).get("error").is_some());
    }
}
