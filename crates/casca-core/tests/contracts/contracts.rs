use casca_core::*;
use serde_json::{json, Value};

fn request(command: Value) -> Value {
    json!({"version":1,"id":42,"command":command})
}
fn target() -> Value {
    json!({"device_ref":"test-band","model":"xiaomi_smart_band7","firmware":"2.0.0.2","hardware":"0.91.177.3"})
}
fn package() -> Value {
    json!({"artifact_ref":"fixture","version":"1.0","sha256":"a".repeat(64),"size_bytes":1024})
}
fn install(op: &str) -> Value {
    json!({"op":op,"target":target(),"package":package(),"authorization_ref":"approval-test"})
}
fn run(value: Value) -> Response {
    handle_json(&serde_json::to_vec(&value).unwrap())
}

#[test]
fn discovery_and_card_boundaries() {
    let result = run(request(json!({"op":"capabilities"})));
    assert_eq!(result.id, Some(42));
    assert!(matches!(result.outcome, Outcome::Capabilities { .. }));
    for (text, valid) in [
        ("".into(), false),
        ("a".repeat(512), true),
        ("a".repeat(513), false),
        ("é".repeat(256), true),
        ("é".repeat(257), false),
        ("\n".into(), false),
        ("\r".into(), false),
        (" \t".into(), false),
    ] {
        let result = run(request(json!({"op":"validate-card","text":text})));
        assert_eq!(
            matches!(result.outcome, Outcome::ValidatedCard { .. }),
            valid
        );
    }
}

#[test]
fn envelopes_are_strict_and_correlated() {
    for id in [1, 42, u64::MAX] {
        let result = run(
            json!({"version":1,"id":id,"command":{"op":"sensors.read","target":target(),"sensor":"heart_rate"}}),
        );
        assert_eq!(result.id, Some(id));
        assert!(matches!(result.outcome, Outcome::Unsupported { .. }));
    }
    for (value, error) in [
        (
            json!({"version":2,"id":99,"command":{"op":"capabilities"}}),
            Error::Version,
        ),
        (
            json!({"version":1,"id":0,"command":{"op":"capabilities"}}),
            Error::RequestId,
        ),
        (
            request(json!({"op":"capabilities","extra":true})),
            Error::InvalidCommand,
        ),
        (request(json!({"op":"unknown"})), Error::InvalidCommand),
    ] {
        let expected_id = value["id"].as_u64();
        let response = run(value);
        assert_eq!(response.id, expected_id);
        assert_eq!(response.outcome, Outcome::Error { code: error });
    }
    for input in [
        b"".as_slice(),
        b"{}",
        b"null",
        b"{\"id\":-1}",
        b"{\"version\":1,\"id\":1,\"id\":2,\"command\":{\"op\":\"capabilities\"}}",
        b"{}{}",
        &[0xff],
    ] {
        let response = handle_json(input);
        assert_eq!(response.id, None);
        assert!(matches!(response.outcome, Outcome::Error { .. }));
    }
}

#[test]
fn missing_or_invalid_mutation_fields_fail_before_authorization() {
    for field in ["target", "package", "authorization_ref"] {
        let mut command = install("miniapps.install");
        command.as_object_mut().unwrap().remove(field);
        assert!(matches!(
            run(request(command)).outcome,
            Outcome::Error {
                code: Error::InvalidCommand
            }
        ));
    }
    for field in ["artifact_ref", "version", "sha256", "size_bytes"] {
        let mut command = install("miniapps.install");
        command["package"].as_object_mut().unwrap().remove(field);
        assert!(matches!(
            run(request(command)).outcome,
            Outcome::Error { .. }
        ));
    }
    for digest in ["a".repeat(63), "a".repeat(65), "g".repeat(64)] {
        let mut command = install("miniapps.install");
        command["package"]["sha256"] = json!(digest);
        assert_eq!(
            run(request(command)).outcome,
            Outcome::Error {
                code: Error::InvalidPackage
            }
        );
    }
    let mut command = install("miniapps.install");
    command["target"]["model"] = json!("xiaomi_smart_band7_pro");
    assert!(matches!(
        run(request(command)).outcome,
        Outcome::Error { .. }
    ));
}

struct ExactApproval(Request);
impl Authorizer for ExactApproval {
    fn permits(&self, request: &Request) -> bool {
        request == &self.0
    }
}

#[test]
fn approval_is_external_bound_to_entire_request_and_never_enables_ble() {
    let value = request(install("miniapps.install"));
    let typed: Request = serde_json::from_value(value.clone()).unwrap();
    assert_eq!(
        dispatch(typed.clone()).outcome,
        Outcome::Error {
            code: Error::AuthorizationRequired
        }
    );
    let approval = ExactApproval(typed.clone());
    assert!(matches!(
        dispatch_with_authorizer(typed.clone(), &approval).outcome,
        Outcome::Unsupported { .. }
    ));
    for (field, value) in [
        ("id", json!(43)),
        ("command", install("watchfaces.install")),
    ] {
        let mut changed = serde_json::to_value(&typed).unwrap();
        changed[field] = value;
        let changed = serde_json::from_value(changed).unwrap();
        assert_eq!(
            dispatch_with_authorizer(changed, &approval).outcome,
            Outcome::Error {
                code: Error::AuthorizationRequired
            }
        );
    }
}

#[test]
fn every_device_variant_is_typed_and_remains_unimplemented() {
    let commands = vec![
        json!({"op":"miniapps.list","target":target()}),
        install("miniapps.install"),
        json!({"op":"miniapps.uninstall","target":target(),"artifact_id":"app-1","authorization_ref":"approval-test"}),
        json!({"op":"watchfaces.list","target":target()}),
        install("watchfaces.install"),
        json!({"op":"watchfaces.uninstall","target":target(),"artifact_id":"face-1","authorization_ref":"approval-test"}),
        json!({"op":"resources.install","target":target(),"package":package(),"kind":"resources","authorization_ref":"approval-test"}),
        json!({"op":"firmware.official","target":target(),"package":package(),"recovery_ref":"recovery-test","authorization_ref":"approval-test"}),
        json!({"op":"firmware.native","target":target(),"package":package(),"recovery_ref":"recovery-test","authorization_ref":"approval-test"}),
        json!({"op":"sensors.read","target":target(),"sensor":"steps"}),
        json!({"op":"interface.show","target":target(),"text":"hello","authorization_ref":"approval-test"}),
        json!({"op":"interface.events","target":target(),"after_sequence":0,"limit":10}),
        json!({"op":"interface.vibrate","target":target(),"duration_ms":100,"authorization_ref":"approval-test"}),
        json!({"op":"interface.image","target":target(),"package":package(),"authorization_ref":"approval-test"}),
        json!({"op":"band.exec","target":target(),"package":package(),"recovery_ref":"recovery-test","authorization_ref":"approval-test"}),
    ];
    assert_eq!(commands.len(), OPERATIONS.len());
    for (command, name) in commands.into_iter().zip(OPERATIONS) {
        let typed: Request = serde_json::from_value(request(command)).unwrap();
        assert_eq!(typed.command.operation(), *name);
        let approval = ExactApproval(typed.clone());
        assert!(matches!(
            dispatch_with_authorizer(typed, &approval).outcome,
            Outcome::Unsupported { .. }
        ));
    }
}

#[test]
fn text_and_json_size_limits_are_exact() {
    assert_eq!(parse(&"x".repeat(1024)), Err(Error::UnknownCommand));
    assert_eq!(parse(&"x".repeat(1025)), Err(Error::InputTooLong));
    for size in [MAX_JSON_BYTES, MAX_JSON_BYTES + 1] {
        let mut bytes = serde_json::to_vec(&request(json!({"op":"capabilities"}))).unwrap();
        bytes.resize(size, b' ');
        assert_eq!(
            matches!(handle_json(&bytes).outcome, Outcome::Capabilities { .. }),
            size == MAX_JSON_BYTES
        );
    }
    for line in [
        "",
        "capabilities extra",
        "capabilities\n",
        "capabilities\r",
        "sh -c id",
        "miniapps.list",
    ] {
        assert!(parse(line).is_err());
    }
    assert!(parse("validate-card $(id); echo x").is_ok());
}

#[test]
fn payload_boundaries_and_unknown_fields_are_rejected() {
    for (field, value, error) in [
        ("artifact_ref", json!("../package"), Error::InvalidPackage),
        ("version", json!(""), Error::InvalidPackage),
        ("size_bytes", json!(0), Error::InvalidPackage),
        (
            "size_bytes",
            json!(64 * 1024 * 1024 + 1_u64),
            Error::InvalidPackage,
        ),
        ("sha256", json!("A".repeat(64)), Error::InvalidPackage),
    ] {
        let mut command = install("miniapps.install");
        command["package"][field] = value;
        assert_eq!(
            run(request(command)).outcome,
            Outcome::Error { code: error }
        );
    }
    for bad in ["".to_string(), "a".repeat(65), "device/real-address".into()] {
        let mut command = json!({"op":"miniapps.list","target":target()});
        command["target"]["device_ref"] = json!(bad);
        assert_eq!(
            run(request(command)).outcome,
            Outcome::Error {
                code: Error::InvalidTarget
            }
        );
    }
    for limit in [0, 101] {
        assert_eq!(
            run(request(
                json!({"op":"interface.events","target":target(),"after_sequence":0,"limit":limit})
            ))
            .outcome,
            Outcome::Error {
                code: Error::InvalidLimit
            }
        );
    }
    for duration in [0, 1001] {
        assert_eq!(run(request(json!({"op":"interface.vibrate","target":target(),"duration_ms":duration,"authorization_ref":"test"}))).outcome, Outcome::Error { code:Error::InvalidDuration });
    }
    for field in ["authorization_ref", "recovery_ref"] {
        let mut command = install("firmware.native");
        command["recovery_ref"] = json!("test");
        command[field] = json!("");
        assert!(matches!(
            run(request(command)).outcome,
            Outcome::Error { .. }
        ));
    }
    let duplicate =
        br#"{"version":1,"id":9,"command":{"op":"validate-card","text":"a","text":"b"}}"#;
    assert_eq!(
        handle_json(duplicate).outcome,
        Outcome::Error {
            code: Error::InvalidCommand
        }
    );
    for command in [
        json!({"op":"sensors.read","target":target(),"sensor":"unknown"}),
        json!({"op":"miniapps.list","target":target(),"extra":1}),
        json!({"op":"miniapps.uninstall","target":target(),"authorization_ref":"test"}),
    ] {
        assert_eq!(
            run(request(command)).outcome,
            Outcome::Error {
                code: Error::InvalidCommand
            }
        );
    }
}

#[test]
fn trusted_rust_callers_cannot_bypass_validation_or_operation_class() {
    let mut typed: Request = serde_json::from_value(request(install("miniapps.install"))).unwrap();
    assert_eq!(typed.command.class(), OperationClass::Mutation);
    if let Command::InstallMiniapp(args) = &mut typed.command {
        args.package.size_bytes = 0;
    }
    let approval = ExactApproval(typed.clone());
    assert_eq!(
        dispatch_with_authorizer(typed, &approval).outcome,
        Outcome::Error {
            code: Error::InvalidPackage
        }
    );
    let mut command = install("firmware.native");
    command["recovery_ref"] = json!("recovery-test");
    let typed: Request = serde_json::from_value(request(command)).unwrap();
    assert_eq!(typed.command.class(), OperationClass::Firmware);
    assert_eq!(
        dispatch(typed).outcome,
        Outcome::Error {
            code: Error::AuthorizationRequired
        }
    );
}

#[test]
fn approval_does_not_survive_payload_or_target_changes() {
    let original = request(install("miniapps.install"));
    let typed: Request = serde_json::from_value(original.clone()).unwrap();
    let approval = ExactApproval(typed);
    for (group, field, value) in [
        ("target", "device_ref", "another-device".to_string()),
        ("target", "firmware", "another-version".to_string()),
        ("package", "sha256", "b".repeat(64)),
        ("package", "artifact_ref", "another-package".to_string()),
    ] {
        let mut changed = original.clone();
        changed["command"][group][field] = json!(value);
        let typed = serde_json::from_value(changed).unwrap();
        assert_eq!(
            dispatch_with_authorizer(typed, &approval).outcome,
            Outcome::Error {
                code: Error::AuthorizationRequired
            }
        );
    }
}
