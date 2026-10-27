use crate::hub;
use serde_json::{json, Value};
use std::time::{Duration, Instant};

/// Newest first; an unknown requested version gets the default.
const VERSIONS: [&str; 4] = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
const DEFAULT_VERSION: &str = "2025-06-18";

// An explicit allowlist of boolean Zepp OS ConfigArg preference keys. No secrets,
// firmware, lock screen, unknown enum values or unrestricted preference writes.
const BOOLEAN_SETTINGS: &[&str] = &[
    "screen_auto_brightness",
    "screen_on_on_notifications",
    "workout_keep_screen_on",
    "always_on_display_follow_watchface",
    "cover_to_mute",
    "vibrate_for_alert",
    "heartrate_activity_monitoring",
    "heartrate_sleep_detection",
    "heartrate_stress_monitoring",
    "spo2_all_day_monitoring_enabled",
    "inactivity_warnings_enable",
    "inactivity_warnings_dnd",
    "fitness_goal_notification",
    "camera_remote",
];

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
            "name": "band_lists",
            "title": "Listas da pulseira",
            "description": "Consulta alarmes/lembretes ou edita somente um alarme já existente (action=set, kind=alarms, slot e pelo menos um campo). Escrita exige autorização do dono no celular.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "action": {"type": "string", "enum": ["get", "set"]},
                    "kind": {"type": "string", "enum": ["alarms", "reminders"]},
                    "device": {"type": "string", "pattern": "^[0-9a-f]{12}$"},
                    "slot": {"type": "integer", "minimum": 0, "maximum": 31, "description": "somente set de alarme existente"},
                    "enabled": {"type": "boolean"},
                    "hour": {"type": "integer", "minimum": 0, "maximum": 23},
                    "minute": {"type": "integer", "minimum": 0, "maximum": 59},
                    "id": {"type":"integer","minimum":0},
                    "message": {"type":"string","minLength":1,"maxLength":200}
                },
                "required": ["action", "kind"],
                "additionalProperties": false
            },
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "openWorldHint": false},
        },
        {
            "name": "band_settings",
            "title": "Configurações da pulseira",
            "description": "Consulta status de autorização, lê opções booleanas conhecidas pelo Gadgetbridge ou solicita sua alteração. Para set, exige aprovação prévia do dono no celular via band_unlock_write. Nunca altera firmware, senha ou pareamento.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "action": {"type": "string", "enum": ["get", "set", "status"]},
                    "device": {"type": "string", "pattern": "^[0-9a-f]{12}$", "description": "id opaco de band_devices"},
                    "key": {"type": "string", "enum": BOOLEAN_SETTINGS},
                    "value": {"type": "boolean", "description": "obrigatório somente para set"}
                },
                "required": ["action"],
                "additionalProperties": false
            },
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "openWorldHint": false},
        },
        {
            "name": "band_unlock_write",
            "title": "Pedir autorização para configurar pulseira",
            "description": "Solicita aprovação no celular para abrir uma janela temporária de escrita. Não grava nenhuma configuração sozinho.",
            "inputSchema": {"type":"object","properties":{"minutes":{"type":"integer","minimum":1,"maximum":60}},"additionalProperties":false},
            "annotations": {"readOnlyHint":false,"destructiveHint":false,"openWorldHint":false},
        },
        {
            "name": "band_capabilities",
            "title": "Capacidades registradas da pulseira",
            "description": "Inventário conservador do que o Gadgetbridge registrou localmente. Uma capacidade não registrada aparece como desconhecida, não como ausente. Nunca expõe MAC, autenticação ou senhas.",
            "inputSchema": {"type": "object", "properties": {"device": {"type": "string", "description": "id de band_devices"}}, "additionalProperties": false},
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
        Some("band_lists") => return band_lists(id, arguments),
        Some("band_settings") => return band_settings(id, arguments),
        Some("band_unlock_write") => {
            if !arguments.is_null() && !arguments.is_object() {
                return error(id, -32602, "expected object");
            }
            if arguments.as_object().is_some_and(|a| a.keys().any(|k| k != "minutes")) {
                return error(id, -32602, "only minutes is accepted");
            }
            let minutes = match arguments.get("minutes") {
                None => 30,
                Some(v) => match v.as_u64() {
                    Some(n @ 1..=60) => n,
                    _ => return error(id, -32602, "minutes must be an integer between 1 and 60"),
                },
            };
            let request = match hub::request_approval(client, 0, &format!("{minutes} min"), "write_unlock") {
                Ok(id) => id,
                Err(_) => return failure(id, "Há solicitações de aprovação demais no celular."),
            };
            let who = client.to_owned();
            std::thread::spawn(move || match hub::wait_decision(request, Duration::from_secs(600)) {
                Some(hub::Decision::Approve) => {
                    hub::lock().write_until = Some(Instant::now() + Duration::from_secs(minutes * 60));
                    hub::log(&format!("janela de escrita aprovada para {who}"));
                }
                Some(hub::Decision::Deny) => hub::log(&format!("janela de escrita negada para {who}")),
                None => hub::log(&format!("solicitação de escrita expirou para {who}")),
            });
            return text(id, format!("Solicitação enviada ao celular. Após a aprovação, a escrita ficará liberada por {minutes} minutos."));
        }
        Some("band_capabilities") => {
            if arguments.is_null() {
                return on_phone(id, "band.capabilities", json!({}), Duration::from_secs(10));
            }
            let Some(args) = arguments.as_object() else {
                return error(id, -32602, "band_capabilities expects an object");
            };
            if args.keys().any(|k| k != "device") {
                return error(id, -32602, "band_capabilities accepts only device");
            }
            let device = match args.get("device") {
                None => Value::Null,
                Some(Value::String(d))
                    if d.len() == 12
                        && d.bytes()
                            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) =>
                {
                    Value::String(d.clone())
                }
                _ => {
                    return error(
                        id,
                        -32602,
                        "device must be a 12-character opaque id from band_devices",
                    );
                }
            };
            return on_phone(
                id,
                "band.capabilities",
                json!({"device": device}),
                Duration::from_secs(10),
            );
        }
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
                    "tools": ["band_devices", "band_capabilities", "band_lists", "band_settings", "band_unlock_write", "band_read", "band_snapshot", "band_notify", "band_find", "band_sync"],
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

/// Reads existing lists or updates an existing alarm slot with explicit phone approval.
/// Alarm creation, deletion and reminder writes are intentionally not exposed.
fn band_lists(id: Value, arguments: &Value) -> Value {
    let Some(args) = arguments.as_object() else {
        return error(id, -32602, "band_lists expects an object");
    };
    if args
        .keys()
        .any(|k| !["action", "kind", "device", "slot", "enabled", "hour", "minute", "id", "message"].contains(&k.as_str()))
    {
        return error(id, -32602, "unknown band_lists argument");
    }
    let action = match args.get("action").and_then(Value::as_str) {
        Some(a @ ("get" | "set")) => a,
        _ => return error(id, -32602, "action must be get or set"),
    };
    let kind = match args.get("kind").and_then(Value::as_str) {
        Some(k @ ("alarms" | "reminders")) => k,
        _ => return error(id, -32602, "kind must be alarms or reminders"),
    };
    let device = match args.get("device") {
        None => Value::Null,
        Some(Value::String(d))
            if d.len() == 12
                && d.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) =>
        {
            Value::String(d.clone())
        }
        _ => return error(id, -32602, "device must be a 12-character opaque id"),
    };
    if action == "get" {
        if ["slot", "enabled", "hour", "minute", "id", "message"].iter().any(|k| args.contains_key(*k)) {
            return error(id, -32602, "get does not accept alarm update fields");
        }
        return on_phone(
            id,
            "band.lists",
            json!({"kind": kind, "action": "get", "device": device}),
            Duration::from_secs(20),
        );
    }
    if kind == "reminders" {
        if ["slot", "enabled", "hour", "minute"].iter().any(|k| args.contains_key(*k)) {
            return error(id, -32602, "reminders accept only id and message");
        }
        let Some(reminder_id) = args.get("id").and_then(Value::as_u64) else {
            return error(id, -32602, "existing reminder id required");
        };
        let Some(message) = args.get("message").and_then(Value::as_str) else {
            return error(id, -32602, "message required");
        };
        if message.trim().is_empty() || message.chars().count() > 200 {
            return error(id, -32602, "message must have 1-200 characters");
        }
        if !hub::write_allowed() {
            return failure(id, "Escrita bloqueada. Solicite band_unlock_write e aguarde aprovação.");
        }
        return on_phone(id, "band.lists", json!({"action":"set","kind":"reminders","device":device,"id":reminder_id,"message":message}), Duration::from_secs(20));
    }
    if args.contains_key("id") || args.contains_key("message") {
        return error(id, -32602, "alarms do not accept reminder fields");
    }
    let slot = match args.get("slot").and_then(Value::as_u64) {
        Some(n @ 0..=31) => n,
        _ => return error(id, -32602, "slot must be an integer between 0 and 31"),
    };
    let enabled = match args.get("enabled") {
        None => None,
        Some(Value::Bool(b)) => Some(*b),
        _ => return error(id, -32602, "enabled must be boolean"),
    };
    let hour = match args.get("hour") {
        None => None,
        Some(v) => match v.as_u64() {
            Some(n @ 0..=23) => Some(n),
            _ => return error(id, -32602, "hour must be an integer between 0 and 23"),
        },
    };
    let minute = match args.get("minute") {
        None => None,
        Some(v) => match v.as_u64() {
            Some(n @ 0..=59) => Some(n),
            _ => return error(id, -32602, "minute must be an integer between 0 and 59"),
        },
    };
    if enabled.is_none() && hour.is_none() && minute.is_none() {
        return error(id, -32602, "set needs enabled, hour or minute");
    }
    if !hub::write_allowed() {
        return failure(id, "Escrita bloqueada. Solicite band_unlock_write e aguarde a aprovação no celular.");
    }
    let mut job = json!({"kind": "alarms", "action": "set", "device": device, "slot": slot});
    if let Some(v) = enabled {
        job["enabled"] = json!(v);
    }
    if let Some(v) = hour {
        job["hour"] = json!(v);
    }
    if let Some(v) = minute {
        job["minute"] = json!(v);
    }
    on_phone(id, "band.lists", job, Duration::from_secs(20))
}

/// Validate the entire settings request before enqueueing a phone job.
/// Supported keys are intentionally limited to ConfigArg.BOOL preferences.
fn band_settings(id: Value, arguments: &Value) -> Value {
    let Some(args) = arguments.as_object() else {
        return error(id, -32602, "band_settings expects an object");
    };
    if args
        .keys()
        .any(|k| !["action", "device", "key", "value"].contains(&k.as_str()))
    {
        return error(id, -32602, "unknown band_settings argument");
    }
    let action = match args.get("action").and_then(Value::as_str) {
        Some(a @ ("get" | "set" | "status")) => a,
        _ => return error(id, -32602, "action must be get, set or status"),
    };
    if action == "status" {
        if args.len() != 1 {
            return error(id, -32602, "status accepts only action");
        }
        return text(id, pretty(&json!({
            "write_allowed": hub::write_allowed(),
            "note": "A aprovação pode expirar; cada escrita é validada novamente no celular."
        })));
    }
    let device = match args.get("device") {
        None => Value::Null,
        Some(Value::String(d))
            if d.len() == 12
                && d.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) =>
        {
            Value::String(d.clone())
        }
        _ => return error(id, -32602, "device must be a 12-character opaque id"),
    };
    let key = match args.get("key") {
        None if action == "get" => Value::Null,
        Some(Value::String(k)) if BOOLEAN_SETTINGS.contains(&k.as_str()) => Value::String(k.clone()),
        _ => return error(id, -32602, "key must be an allowed boolean setting"),
    };
    if action == "get" {
        if args.contains_key("value") {
            return error(id, -32602, "value is not allowed for get");
        }
        return on_phone(id, "band.settings", json!({"action": action, "device": device, "key": key}), Duration::from_secs(10));
    }
    let Some(value) = args.get("value").and_then(Value::as_bool) else {
        return error(id, -32602, "set requires a boolean value");
    };
    if !hub::write_allowed() {
        return failure(id, "Escrita bloqueada. Solicite band_unlock_write e aguarde a aprovação no celular.");
    }
    on_phone(id, "band.settings", json!({"action": action, "device": device, "key": key, "value": value}), Duration::from_secs(10))
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
