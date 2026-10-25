use serde_json::{json, Value};
use std::io::Write;
use std::process::{Command, Stdio};

fn console(input: &[u8], json_mode: bool) -> (i32, Value) {
    let mut command = Command::new(env!("CARGO_BIN_EXE_band7-terminal"));
    if json_mode {
        command.arg("--json");
    }
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    let output = child.wait_with_output().unwrap();
    (
        output.status.code().unwrap(),
        serde_json::from_slice(&output.stdout).unwrap(),
    )
}
#[test]
fn human_console_framing_and_input_limits() {
    for input in [
        b"capabilities".as_slice(),
        b"capabilities\n",
        b"capabilities\r\n",
    ] {
        let (code, body) = console(input, false);
        assert_eq!(code, 0);
        assert_eq!(body["id"], 1);
        assert_eq!(body["mode"], "offline");
        assert_eq!(
            body["implemented"],
            json!(["capabilities", "validate-card"])
        );
    }
    for input in [
        vec![],
        vec![0xff],
        vec![b'x'; 1024],
        vec![b'x'; 1025],
        b"capabilities\r".to_vec(),
        b"capabilities\ncapabilities\n".to_vec(),
        b"capabilities extra".to_vec(),
    ] {
        assert_eq!(console(&input, false).0, 1);
    }
    for (size, code) in [(0, 1), (512, 0), (513, 1)] {
        assert_eq!(
            console(
                format!("validate-card {}", "a".repeat(size)).as_bytes(),
                false
            )
            .0,
            code
        );
    }
    let (code, body) = console(b"miniapps.install", false);
    assert_eq!(code, 1);
    assert_eq!(body["code"], "parameters_required");
}
#[test]
fn json_console_correlates_success_errors_and_unsupported() {
    for (id, version, command, code, status) in [
        (17, 1, json!({"op":"capabilities"}), 0, "capabilities"),
        (18, 2, json!({"op":"capabilities"}), 1, "error"),
        (0, 1, json!({"op":"capabilities"}), 1, "error"),
        (19, 1, json!({"op":"missing"}), 1, "error"),
        (
            20,
            1,
            json!({"op":"miniapps.list","target":{"device_ref":"test","model":"xiaomi_smart_band7","firmware":"2.0","hardware":"1.0"}}),
            2,
            "unsupported",
        ),
    ] {
        let input =
            serde_json::to_vec(&json!({"id":id,"version":version,"command":command})).unwrap();
        let (actual, body) = console(&input, true);
        assert_eq!(actual, code);
        assert_eq!(body["id"], id);
        assert_eq!(body["version"], 1);
        assert_eq!(body["status"], status);
    }
    for input in [b"{}".as_slice(), b"{", &[0xff], b"{}\n{}"] {
        let (code, body) = console(input, true);
        assert_eq!(code, 1);
        assert_eq!(body["id"], Value::Null);
    }
}
