//! Host development server: the phone's MCP without the phone.
//! Prints each pairing request; type `y <id>` or `n <id>` to decide.
//! A synthetic band snapshot is published so band_snapshot has data.
use casca_mcp::control;
use serde_json::{json, Value};
use std::{io::BufRead, thread, time::Duration};

fn ctl(request: Value) -> Value {
    serde_json::from_slice(&control(request.to_string().as_bytes())).expect("control replies JSON")
}

fn main() {
    let port: u64 = std::env::args()
        .nth(1)
        .and_then(|p| p.parse().ok())
        .unwrap_or(8765);
    let dir = std::env::temp_dir().join("casca-mcp-dev");
    let started = ctl(json!({"op": "start", "dir": dir.to_str().unwrap(), "port": port}));
    let Some(port) = started["port"].as_u64() else {
        panic!("start failed: {started}")
    };
    let fixture = json!({"version": 1, "id": 1, "result": {"delivery": "cached_snapshot", "synthetic": true,
        "fields": {"battery": {"state": "received", "value": 74}}}});
    ctl(json!({"op": "publish", "snapshot": fixture}));
    println!(
        "MCP em http://127.0.0.1:{port}/mcp (tokens em {})",
        dir.display()
    );
    println!("Parear: curl -N -X POST http://127.0.0.1:{port}/pair -d '{{\"client\":\"dev\"}}'");
    thread::spawn(|| {
        let mut seen = 0;
        loop {
            for p in ctl(json!({"op": "status"}))["pending"]
                .as_array()
                .cloned()
                .unwrap_or_default()
            {
                let id = p["id"].as_u64().unwrap_or(0);
                if id > seen {
                    seen = id;
                    println!(
                        "Pedido {id}: {} de {} com código {}. Responda: y {id} | n {id}",
                        p["client"], p["peer"], p["code"]
                    );
                }
            }
            thread::sleep(Duration::from_millis(200));
        }
    });
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let mut parts = line.split_whitespace();
        if let (Some(answer @ ("y" | "n")), Some(Ok(id))) =
            (parts.next(), parts.next().map(str::parse::<u64>))
        {
            println!(
                "{}",
                ctl(json!({"op": "decide", "id": id, "approve": answer == "y"}))
            );
        }
    }
}
