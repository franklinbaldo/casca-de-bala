use crate::hub;
use serde_json::{json, Value};

/// Newest first; an unknown requested version gets the default.
const VERSIONS: [&str; 4] = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
const DEFAULT_VERSION: &str = "2025-06-18";

pub(crate) fn error(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message}})
}

fn ok(id: Value, result: Value) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "result": result})
}

/// One JSON-RPC message in, at most one reply out. Notifications and client
/// responses (no `id` or no `method`) get no reply: HTTP 202.
pub(crate) fn dispatch(message: &Value) -> Option<Value> {
    let Some(object) = message.as_object() else {
        return Some(error(
            Value::Null,
            -32600,
            "single JSON-RPC object expected",
        ));
    };
    let id = object.get("id").cloned()?;
    if object.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return Some(error(id, -32600, "jsonrpc 2.0 required"));
    }
    let method = object.get("method").and_then(Value::as_str)?;
    let params = object.get("params").cloned().unwrap_or(Value::Null);
    Some(match method {
        "initialize" => {
            let requested = params["protocolVersion"].as_str().unwrap_or("");
            let version = VERSIONS
                .iter()
                .find(|v| **v == requested)
                .copied()
                .unwrap_or(DEFAULT_VERSION);
            ok(
                id,
                json!({
                    "protocolVersion": version,
                    "capabilities": {"tools": {"listChanged": false}},
                    "serverInfo": {"name": "casca-de-bala", "version": env!("CARGO_PKG_VERSION")},
                    "instructions": "Hub no celular do dono: intermedeia os dispositivos dele (hoje a Xiaomi Smart Band 7). Comece por casca_capabilities.",
                }),
            )
        }
        "ping" => ok(id, json!({})),
        "tools/list" => ok(id, json!({"tools": tools()})),
        "tools/call" => call(id, &params),
        _ => error(id, -32601, "method not found"),
    })
}

fn tools() -> Value {
    let empty = json!({"type": "object", "properties": {}, "additionalProperties": false});
    json!([
        {
            "name": "casca_capabilities",
            "title": "Dispositivos do Casca",
            "description": "Lista os dispositivos que este celular intermedeia e o que cada um oferece agora.",
            "inputSchema": empty,
            "annotations": {"readOnlyHint": true, "openWorldHint": false},
        },
        {
            "name": "band_snapshot",
            "title": "Última leitura da pulseira",
            "description": "Última leitura da Xiaomi Smart Band 7 feita pelo app no celular: bateria, revisões de hardware/software e PnP, com o horário. Não conecta na pulseira; para atualizar, o dono abre o Casca e lê.",
            "inputSchema": empty,
            "annotations": {"readOnlyHint": true, "openWorldHint": false},
        },
    ])
}

fn text(id: Value, body: String) -> Value {
    ok(
        id,
        json!({"content": [{"type": "text", "text": body}], "isError": false}),
    )
}

fn call(id: Value, params: &Value) -> Value {
    if !(params["arguments"].is_null()
        || params["arguments"]
            .as_object()
            .is_some_and(|a| a.is_empty()))
    {
        return error(id, -32602, "these tools take no arguments");
    }
    match params["name"].as_str() {
        Some("casca_capabilities") => {
            let snapshot_at = hub::lock().snapshot.as_ref().map(|(_, at)| *at);
            let body = json!({
                "server": "casca-de-bala",
                "version": env!("CARGO_PKG_VERSION"),
                "devices": [{
                    "id": "band",
                    "model": "Xiaomi Smart Band 7",
                    "access": "última leitura feita pelo app (cache); sem conexão em segundo plano",
                    "last_reading_unix_ms": snapshot_at,
                    "tools": ["band_snapshot"],
                }],
            });
            text(id, serde_json::to_string_pretty(&body).unwrap_or_default())
        }
        Some("band_snapshot") => match hub::lock().snapshot.clone() {
            Some((snapshot, received)) => {
                let body = json!({"received_unix_ms": received, "snapshot": snapshot});
                text(id, serde_json::to_string_pretty(&body).unwrap_or_default())
            }
            None => text(
                id,
                "Nenhuma leitura ainda. Peça ao dono para abrir o Casca de Bala e ler a pulseira."
                    .to_owned(),
            ),
        },
        _ => error(id, -32602, "unknown tool"),
    }
}
