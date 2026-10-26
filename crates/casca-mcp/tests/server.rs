use casca_mcp::control;
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::TcpStream,
    path::PathBuf,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

fn ctl(request: Value) -> Value {
    serde_json::from_slice(&control(request.to_string().as_bytes())).unwrap()
}

fn http(
    port: u16,
    method: &str,
    path: &str,
    headers: &[(&str, &str)],
    body: &str,
) -> (u16, String) {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
    let mut request = format!(
        "{method} {path} HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n",
        body.len()
    );
    for (name, value) in headers {
        request += &format!("{name}: {value}\r\n");
    }
    request += "\r\n";
    request += body;
    stream.write_all(request.as_bytes()).unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    let status = response[9..12].parse().unwrap();
    let body = response
        .split_once("\r\n\r\n")
        .map(|(_, b)| b.to_owned())
        .unwrap_or_default();
    (status, body)
}

fn rpc(port: u16, token: &str, message: Value) -> (u16, String) {
    let auth = format!("Bearer {token}");
    http(
        port,
        "POST",
        "/mcp",
        &[
            ("Authorization", &auth),
            ("Content-Type", "application/json"),
        ],
        &message.to_string(),
    )
}

fn pending_code(client: &str) -> (u64, u64) {
    for _ in 0..200 {
        let status = ctl(json!({"op": "status"}));
        if let Some(p) = status["pending"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["client"] == client)
        {
            return (p["id"].as_u64().unwrap(), p["code"].as_u64().unwrap());
        }
        thread::sleep(Duration::from_millis(10));
    }
    panic!("pairing request never became pending");
}

/// Starts pairing, checks that the streamed code matches the phone's, decides it.
fn pair(port: u16, client: &str, approve: bool) -> String {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
    let body = json!({"client": client}).to_string();
    write!(
        stream,
        "POST /pair HTTP/1.1\r\nHost: x\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
    .unwrap();
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    while line != "\r\n" {
        line.clear();
        reader.read_line(&mut line).unwrap();
    }
    line.clear();
    reader.read_line(&mut line).unwrap();
    let (id, code) = pending_code(client);
    assert!(line.contains(&format!("código {code} ")), "{line}");
    assert_eq!(
        ctl(json!({"op": "decide", "id": id, "approve": approve}))["ok"],
        true
    );
    let mut rest = String::new();
    reader.read_to_string(&mut rest).unwrap();
    rest
}

#[test]
fn pairing_tools_and_restart() {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir: PathBuf = std::env::temp_dir().join(format!("casca-mcp-test-{nonce}"));
    let dir_text = dir.to_str().unwrap();

    let started = ctl(json!({"op": "start", "dir": dir_text, "port": 0}));
    let port = started["port"].as_u64().unwrap() as u16;
    assert_eq!(
        ctl(json!({"op": "start", "dir": dir_text, "port": 0}))["error"],
        "already_running"
    );
    assert_eq!(
        ctl(json!({"op": "start", "dir": dir_text, "port": 80}))["error"],
        "invalid_request"
    );
    assert_eq!(http(port, "GET", "/", &[], "").0, 200);

    // No token, wrong token, browser origin: nothing reaches the protocol.
    let init = json!({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}});
    assert_eq!(http(port, "POST", "/mcp", &[], &init.to_string()).0, 401);
    assert_eq!(rpc(port, "casca_wrong", init.clone()).0, 401);
    assert_eq!(
        http(
            port,
            "POST",
            "/pair",
            &[("Origin", "https://evil.example")],
            r#"{"client":"x"}"#
        )
        .0,
        403
    );
    assert_eq!(http(port, "POST", "/pair", &[], r#"{"client":""}"#).0, 400);

    assert!(pair(port, "intruso", false).contains("Negado"));
    let approved = pair(port, "jatoba", true);
    let token = approved
        .lines()
        .find_map(|l| l.strip_prefix("CASCA_TOKEN="))
        .expect(&approved)
        .to_owned();
    assert!(token.starts_with("casca_") && token.len() == 6 + 43);
    assert_eq!(ctl(json!({"op": "status"}))["clients"][0]["name"], "jatoba");

    let (status, body) = rpc(port, &token, init);
    assert_eq!(status, 200);
    let reply: Value = serde_json::from_str(&body).unwrap();
    assert_eq!(reply["result"]["protocolVersion"], "2025-06-18");
    assert_eq!(reply["result"]["serverInfo"]["name"], "casca-de-bala");
    let unknown_version = json!({"jsonrpc": "2.0", "id": 2, "method": "initialize", "params": {"protocolVersion": "1999-01-01"}});
    let reply: Value = serde_json::from_str(&rpc(port, &token, unknown_version).1).unwrap();
    assert_eq!(reply["result"]["protocolVersion"], "2025-06-18");
    assert_eq!(
        rpc(
            port,
            &token,
            json!({"jsonrpc": "2.0", "method": "notifications/initialized"})
        )
        .0,
        202
    );
    assert_eq!(
        http(
            port,
            "GET",
            "/mcp",
            &[("Authorization", &format!("Bearer {token}"))],
            ""
        )
        .0,
        405
    );

    let reply: Value = serde_json::from_str(
        &rpc(
            port,
            &token,
            json!({"jsonrpc": "2.0", "id": 3, "method": "tools/list"}),
        )
        .1,
    )
    .unwrap();
    let names: Vec<&str> = reply["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["casca_capabilities", "band_snapshot"]);

    let call = |name: &str| -> Value {
        let message = json!({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": name, "arguments": {}}});
        serde_json::from_str(&rpc(port, &token, message).1).unwrap()
    };
    assert!(call("band_snapshot")["result"]["content"][0]["text"]
        .as_str()
        .unwrap()
        .starts_with("Nenhuma leitura"));
    assert_eq!(call("nope")["error"]["code"], -32602);

    assert_eq!(
        ctl(json!({"op": "publish", "snapshot": {"error": "x"}}))["error"],
        "snapshot_error"
    );
    let published = json!({"version": 1, "id": 7, "result": {"delivery": "cached_snapshot", "fields": {"battery": {"state": "received", "value": 74}}}});
    assert_eq!(
        ctl(json!({"op": "publish", "snapshot": published}))["ok"],
        true
    );
    let text = call("band_snapshot")["result"]["content"][0]["text"]
        .as_str()
        .unwrap()
        .to_owned();
    let snapshot: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(snapshot["snapshot"]["fields"]["battery"]["value"], 74);
    assert!(call("casca_capabilities")["result"]["content"][0]["text"]
        .as_str()
        .unwrap()
        .contains("Xiaomi Smart Band 7"));

    // Tokens survive a restart; the listener really closes on stop.
    assert_eq!(ctl(json!({"op": "stop"}))["ok"], true);
    thread::sleep(Duration::from_millis(100));
    assert!(TcpStream::connect(("127.0.0.1", port)).is_err());
    let port = ctl(json!({"op": "start", "dir": dir_text, "port": 0}))["port"]
        .as_u64()
        .unwrap() as u16;
    assert_eq!(
        rpc(
            port,
            &token,
            json!({"jsonrpc": "2.0", "id": 5, "method": "ping"})
        )
        .0,
        200
    );
    assert_eq!(
        ctl(json!({"op": "forget", "client": "jatoba"}))["removed"],
        1
    );
    assert_eq!(
        rpc(
            port,
            &token,
            json!({"jsonrpc": "2.0", "id": 6, "method": "ping"})
        )
        .0,
        401
    );
    ctl(json!({"op": "stop"}));
    let _ = std::fs::remove_dir_all(dir);
}
