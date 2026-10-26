use crate::hub;
use serde_json::{json, Value};
use std::time::Duration;

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
    let read_only = json!({"readOnlyHint": true, "openWorldHint": false});
    json!([
        {
            "name": "casca_capabilities",
            "title": "Dispositivos do Casca",
            "description": "Lista os dispositivos que este celular intermedeia e o que cada um oferece agora.",
            "inputSchema": empty,
            "annotations": read_only,
        },
        {
            "name": "band_devices",
            "title": "Pulseiras vinculadas",
            "description": "Lista as pulseiras já vinculadas ao Bluetooth do celular, com um id opaco para band_read. Não busca dispositivos novos nem expõe endereços Bluetooth.",
            "inputSchema": empty,
            "annotations": read_only,
        },
        {
            "name": "band_read",
            "title": "Ler a pulseira agora",
            "description": "Conecta na pulseira pelo celular e lê bateria, revisões de hardware/software e PnP (leva até ~20 s). Sem device, usa a última lida ou a única vinculada. Somente leitura: não escreve na pulseira nem pareia.",
            "inputSchema": {
                "type": "object",
                "properties": {"device": {"type": "string", "description": "id vindo de band_devices"}},
                "additionalProperties": false,
            },
            "annotations": read_only,
        },
        {
            "name": "band_snapshot",
            "title": "Última leitura da pulseira",
            "description": "Última leitura já feita (pelo app ou por band_read), com o horário, sem conectar na pulseira.",
            "inputSchema": empty,
            "annotations": read_only,
        },
    ])
}

fn text(id: Value, body: String) -> Value {
    ok(
        id,
        json!({"content": [{"type": "text", "text": body}], "isError": false}),
    )
}

fn failure(id: Value, body: &str) -> Value {
    ok(
        id,
        json!({"content": [{"type": "text", "text": body}], "isError": true}),
    )
}

fn pretty(value: &Value) -> String {
    serde_json::to_string_pretty(value).unwrap_or_default()
}

/// Runs a job on the phone and turns its outcome into tool output.
fn on_phone(id: Value, kind: &'static str, args: Value, wait: Duration) -> Value {
    match hub::run_job(kind, args, wait) {
        Ok(result) => match result.get("error").and_then(Value::as_str) {
            Some(message) => failure(id, message),
            None => text(id, pretty(&result)),
        },
        Err("busy") => failure(id, "O celular está com tarefas demais na fila; tente de novo em instantes."),
        Err(_) => failure(id, "O celular não respondeu a tempo. O serviço do Casca pode estar parado ou o Bluetooth ocupado."),
    }
}

fn call(id: Value, params: &Value) -> Value {
    let arguments = &params["arguments"];
    let no_arguments = arguments.is_null() || arguments.as_object().is_some_and(|a| a.is_empty());
    match params["name"].as_str() {
        Some("band_read") => {
            let device = match arguments.get("device") {
                None | Some(Value::Null) => Value::Null,
                Some(Value::String(d)) if d.len() <= 64 => Value::String(d.clone()),
                _ => return error(id, -32602, "device must be an id from band_devices"),
            };
            if arguments
                .as_object()
                .is_some_and(|a| a.keys().any(|k| k != "device"))
            {
                return error(id, -32602, "band_read takes only device");
            }
            on_phone(
                id,
                "band.read",
                json!({"device": device}),
                Duration::from_secs(40),
            )
        }
        _ if !no_arguments => error(id, -32602, "this tool takes no arguments"),
        Some("band_devices") => on_phone(id, "band.devices", json!({}), Duration::from_secs(10)),
        Some("casca_capabilities") => {
            let snapshot_at = hub::lock().snapshot.as_ref().map(|(_, at)| *at);
            let body = json!({
                "server": "casca-de-bala",
                "version": env!("CARGO_PKG_VERSION"),
                "devices": [{
                    "id": "band",
                    "model": "Xiaomi Smart Band 7",
                    "access": "leitura sob demanda pelo celular (band_read) e última leitura em cache (band_snapshot)",
                    "last_reading_unix_ms": snapshot_at,
                    "tools": ["band_devices", "band_read", "band_snapshot"],
                }],
            });
            text(id, pretty(&body))
        }
        Some("band_snapshot") => match hub::lock().snapshot.clone() {
            Some((snapshot, received)) => text(
                id,
                pretty(&json!({"received_unix_ms": received, "snapshot": snapshot})),
            ),
            None => text(
                id,
                "Nenhuma leitura ainda. Use band_read para ler a pulseira agora.".to_owned(),
            ),
        },
        _ => error(id, -32602, "unknown tool"),
    }
}
