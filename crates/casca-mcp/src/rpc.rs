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
pub(crate) fn dispatch(message: &Value, client: &str) -> Option<Value> {
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
        "tools/list" => {
            #[allow(unused_mut)] // extended only with the lab feature
            let mut list = tools().as_array().cloned().unwrap_or_default();
            #[cfg(feature = "lab")]
            list.extend(crate::lab::tools());
            ok(id, json!({"tools": list}))
        }
        "tools/call" => call(id, &params, client),
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
            "title": "Ler a pulseira e seus dados de saúde",
            "description": "Estado da pulseira (bateria, conexão, firmware) e o que o celular já guardou dela nas últimas horas: passos, frequência cardíaca, sono (leve/profundo/REM), estresse, SpO2, FC de repouso e nota do sono, com totais e série por hora (até 48 h) ou por dia. Lê o banco do celular, sem conectar na pulseira; use band_sync antes para trazer o mais recente. Sem device, usa a única pulseira conectada.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": {"type": "string", "description": "id vindo de band_devices"},
                    "hours": {"type": "integer", "minimum": 1, "maximum": 168, "description": "janela em horas até agora (padrão 24)"},
                },
                "additionalProperties": false,
            },
            "annotations": read_only,
        },
        {
            "name": "band_notify",
            "title": "Mensagem no pulso",
            "description": "Mostra uma notificação no celular, que o app que conduz a pulseira (Gadgetbridge ou Zepp Life) leva ao pulso com vibração. Precisa que esse app tenha o Casca de Bala liberado na lista de notificações.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "maxLength": 64, "description": "título curto"},
                    "body": {"type": "string", "maxLength": 512, "description": "texto da mensagem"},
                },
                "required": ["title"],
                "additionalProperties": false,
            },
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false},
        },
        {
            "name": "band_find",
            "title": "Fazer a pulseira vibrar",
            "description": "Aciona \"encontrar dispositivo\": a pulseira vibra por alguns segundos. Precisa de um app que conduza a pulseira com autenticação (o Casca sobre o Gadgetbridge).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": {"type": "string", "description": "id vindo de band_devices"},
                    "seconds": {"type": "integer", "minimum": 1, "maximum": 30, "description": "duração (padrão 5)"},
                },
                "additionalProperties": false,
            },
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false},
        },
        {
            "name": "band_sync",
            "title": "Sincronizar dados da pulseira",
            "description": "Pede ao app que conduz a pulseira para buscar os dados gravados nela (atividade, frequência cardíaca, sono, SpO2…). Responde quando o pedido foi aceito; a sincronização segue em segundo plano.",
            "inputSchema": {
                "type": "object",
                "properties": {"device": {"type": "string", "description": "id vindo de band_devices"}},
                "additionalProperties": false,
            },
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false},
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
pub(crate) fn on_phone(id: Value, kind: &'static str, args: Value, wait: Duration) -> Value {
    match hub::run_job(kind, args, wait) {
        Ok(result) => match result.get("error").and_then(Value::as_str) {
            Some(message) => failure(id, message),
            None => text(id, pretty(&result)),
        },
        Err("busy") => failure(id, "O celular está com tarefas demais na fila; tente de novo em instantes."),
        Err(_) => failure(id, "O celular não respondeu a tempo. O serviço do Casca pode estar parado ou o Bluetooth ocupado."),
    }
}

fn call(id: Value, params: &Value, client: &str) -> Value {
    let arguments = &params["arguments"];
    #[cfg(feature = "lab")]
    if let Some(reply) = params["name"]
        .as_str()
        .and_then(|name| crate::lab::call(id.clone(), name, arguments, client))
    {
        return reply;
    }
    match params["name"].as_str() {
        Some("band_notify") => return band_notify(id, arguments, client),
        Some("band_find") => return band_find(id, arguments, client),
        Some("band_sync") => return band_sync(id, arguments, client),
        _ => {}
    }
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
                .is_some_and(|a| a.keys().any(|k| k != "device" && k != "hours"))
            {
                return error(id, -32602, "band_read takes only device and hours");
            }
            let hours = match arguments.get("hours") {
                None | Some(Value::Null) => 24,
                Some(h) => match h.as_u64() {
                    Some(h @ 1..=168) => h,
                    _ => return error(id, -32602, "hours must be an integer from 1 to 168"),
                },
            };
            on_phone(
                id,
                "band.read",
                json!({"device": device, "hours": hours}),
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
                    "access": "estado e dados de saúde guardados pelo celular (band_read), mensagens e vibração no pulso (band_notify, band_find) e sincronização (band_sync)",
                    "last_reading_unix_ms": snapshot_at,
                    "tools": ["band_devices", "band_read", "band_snapshot", "band_notify", "band_find", "band_sync"],
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

/// Text that fits a notification: present, trimmed, within `max` characters.
fn short_text(value: Option<&Value>, max: usize, required: bool) -> Result<String, ()> {
    match value {
        None | Some(Value::Null) if !required => Ok(String::new()),
        Some(Value::String(s)) if !s.trim().is_empty() && s.chars().count() <= max => {
            Ok(s.trim().to_owned())
        }
        Some(Value::String(s)) if s.is_empty() && !required => Ok(String::new()),
        _ => Err(()),
    }
}

fn band_notify(id: Value, arguments: &Value, client: &str) -> Value {
    let Some(args) = arguments.as_object() else {
        return error(id, -32602, "band_notify takes title and body");
    };
    if args.keys().any(|k| k != "title" && k != "body") {
        return error(id, -32602, "band_notify takes only title and body");
    }
    let (Ok(title), Ok(body)) = (
        short_text(args.get("title"), 64, true),
        short_text(args.get("body"), 512, false),
    ) else {
        return error(
            id,
            -32602,
            "title (1-64 characters) and body (up to 512) must be text",
        );
    };
    // Who sent it, never what: the text may be private.
    hub::log(&format!("band_notify por {client}"));
    on_phone(
        id,
        "band.notify",
        json!({"title": title, "body": body}),
        Duration::from_secs(10),
    )
}

/// `device` (optional id from band_devices) plus only the keys in `extra`.
fn device_args(id: &Value, arguments: &Value, tool: &str, extra: &[&str]) -> Result<Value, Value> {
    let empty = serde_json::Map::new();
    let args = match arguments {
        Value::Null => &empty,
        Value::Object(args) => args,
        _ => {
            return Err(error(
                id.clone(),
                -32602,
                &format!("{tool} takes an object"),
            ))
        }
    };
    if args
        .keys()
        .any(|k| k != "device" && !extra.contains(&k.as_str()))
    {
        return Err(error(
            id.clone(),
            -32602,
            &format!("{tool}: unknown argument"),
        ));
    }
    match args.get("device") {
        None | Some(Value::Null) => Ok(Value::Null),
        Some(Value::String(d)) if !d.is_empty() && d.len() <= 64 => Ok(Value::String(d.clone())),
        _ => Err(error(
            id.clone(),
            -32602,
            "device must be an id from band_devices",
        )),
    }
}

fn band_find(id: Value, arguments: &Value, client: &str) -> Value {
    let device = match device_args(&id, arguments, "band_find", &["seconds"]) {
        Ok(device) => device,
        Err(reply) => return reply,
    };
    let seconds = match arguments.get("seconds") {
        None | Some(Value::Null) => 5,
        Some(v) => match v.as_u64() {
            Some(s @ 1..=30) => s,
            _ => return error(id, -32602, "seconds must be 1-30"),
        },
    };
    hub::log(&format!("band_find por {client}: {seconds} s"));
    on_phone(
        id,
        "band.find",
        json!({"device": device, "seconds": seconds}),
        Duration::from_secs(15),
    )
}

fn band_sync(id: Value, arguments: &Value, client: &str) -> Value {
    let device = match device_args(&id, arguments, "band_sync", &[]) {
        Ok(device) => device,
        Err(reply) => return reply,
    };
    hub::log(&format!("band_sync por {client}"));
    on_phone(
        id,
        "band.sync",
        json!({"device": device}),
        Duration::from_secs(15),
    )
}
