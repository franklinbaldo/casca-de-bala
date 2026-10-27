//! Lab tools: the AI explores and drives Bluetooth through the phone. Device
//! work runs as `ble.*`/`lab.*` jobs taken by the Rust BLE worker in the app.
//! Writes need a time window the owner approves on the phone.
use crate::hub;
use serde_json::{json, Map, Value};
use std::time::{Duration, Instant};

/// How long a write-unlock request stays answerable on the phone. The tool
/// call returns at once (clients often cut calls at 60 s); a thread waits.
const UNLOCK_WAIT: Duration = Duration::from_secs(600);

fn schema(properties: Value, required: &[&str]) -> Value {
    json!({"type": "object", "properties": properties, "required": required, "additionalProperties": false})
}

pub(crate) fn tools() -> Vec<Value> {
    let device = json!({"type": "string", "description": "id de ble_scan ou band_devices"});
    let characteristic = json!({"type": "string", "description": "UUID curto (2a19) ou completo"});
    let service =
        json!({"type": "string", "description": "UUID do serviço, se a característica se repetir"});
    let read_only = json!({"readOnlyHint": true, "openWorldHint": false});
    vec![
        json!({"name": "lab_info", "title": "Diagnóstico do celular",
            "description": "Aparelho, Android, estado do Bluetooth, permissões do app e versão do Casca.",
            "inputSchema": schema(json!({}), &[]), "annotations": read_only}),
        json!({"name": "lab_log", "title": "Log do laboratório",
            "description": "Últimas linhas do log do app (timestamp em ms + evento).",
            "inputSchema": schema(json!({"lines": {"type": "integer", "minimum": 1, "maximum": 300}}), &[]),
            "annotations": read_only}),
        json!({"name": "ble_scan", "title": "Scan BLE",
            "description": "Escuta anúncios BLE por alguns segundos: nome, RSSI, serviços, dados de fabricante e de serviço. Inclui as vinculadas. Ids são opacos.",
            "inputSchema": schema(json!({"seconds": {"type": "integer", "minimum": 1, "maximum": 15}}), &[]),
            "annotations": read_only}),
        json!({"name": "ble_explore", "title": "Explorar GATT",
            "description": "Conecta e lista todos os serviços, características (propriedades) e descritores, lendo as características legíveis.",
            "inputSchema": schema(json!({"device": device}), &["device"]), "annotations": read_only}),
        json!({"name": "ble_read", "title": "Ler característica",
            "description": "Conecta e lê uma característica (hex e texto).",
            "inputSchema": schema(json!({"device": device, "characteristic": characteristic, "service": service}), &["device", "characteristic"]),
            "annotations": read_only}),
        json!({"name": "ble_notify", "title": "Escutar notificações",
            "description": "Conecta, assina uma característica com NOTIFY/INDICATE e devolve as notificações recebidas durante alguns segundos.",
            "inputSchema": schema(json!({"device": device, "characteristic": characteristic, "service": service,
                "seconds": {"type": "integer", "minimum": 1, "maximum": 60}}), &["device", "characteristic"]),
            "annotations": read_only}),
        json!({"name": "ble_write", "title": "Escrever característica",
            "description": "Escreve bytes (hex) numa característica. Exige escrita liberada no celular (lab_unlock_write). Pode mudar o estado do dispositivo. Serviços de atualização de firmware (DFU) são sempre recusados.",
            "inputSchema": schema(json!({"device": device, "characteristic": characteristic, "service": service,
                "value_hex": {"type": "string", "description": "bytes em hex, até 512"},
                "response": {"type": "boolean", "description": "write with response (padrão true)"}}), &["device", "characteristic", "value_hex"]),
            "annotations": {"readOnlyHint": false, "destructiveHint": true, "openWorldHint": false}}),
        json!({"name": "huami_import", "title": "Importar chave Huami",
            "description": "Entrega ao celular a chave Huami da pulseira (auth_key, 32 hex, com device) ou uma sessão do Zepp (apptoken e userid), com a qual o próprio celular busca as chaves da conta. Exige escrita liberada no celular (lab_unlock_write). A chave fica só no armazenamento privado do app; nada disso vai para o lab_log.",
            "inputSchema": schema(json!({"device": device,
                "auth_key": {"type": "string", "description": "32 dígitos hex (0x opcional); exige device"},
                "apptoken": {"type": "string", "description": "apptoken de uma sessão do Zepp"},
                "userid": {"type": "string", "description": "userid da mesma sessão"}}), &[]),
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "openWorldHint": true}}),
        json!({"name": "lab_unlock_write", "title": "Pedir liberação de escrita",
            "description": "Pede ao dono, por notificação no celular, para liberar ble_write por alguns minutos. Responde na hora; o pedido vale 10 min e a liberação aparece em lab_info (write_allowed).",
            "inputSchema": schema(json!({"minutes": {"type": "integer", "minimum": 1, "maximum": 60}}), &[]),
            "annotations": {"readOnlyHint": false, "openWorldHint": false}}),
    ]
}

fn ok_text(id: Value, body: String, error: bool) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "result": {"content": [{"type": "text", "text": body}], "isError": error}})
}

fn bad(id: Value, message: &str) -> Value {
    crate::rpc::error(id, -32602, message)
}

struct Args<'a>(&'a Map<String, Value>);

impl Args<'_> {
    fn text(&self, key: &str, max: usize) -> Result<Option<String>, String> {
        match self.0.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(s)) if !s.is_empty() && s.len() <= max => Ok(Some(s.clone())),
            _ => Err(format!("{key} inválido")),
        }
    }
    fn int(
        &self,
        key: &str,
        range: std::ops::RangeInclusive<u64>,
        default: u64,
    ) -> Result<u64, String> {
        match self.0.get(key) {
            None | Some(Value::Null) => Ok(default),
            Some(v) => v
                .as_u64()
                .filter(|n| range.contains(n))
                .ok_or(format!("{key} fora de {range:?}")),
        }
    }
    fn only(&self, allowed: &[&str]) -> Result<(), String> {
        match self.0.keys().find(|k| !allowed.contains(&k.as_str())) {
            Some(k) => Err(format!("argumento desconhecido: {k}")),
            None => Ok(()),
        }
    }
}

/// The device/characteristic/service triple shared by read, notify and write.
fn target(args: &Args) -> Result<Map<String, Value>, String> {
    let mut job = Map::new();
    job.insert(
        "device".into(),
        json!(args.text("device", 64)?.ok_or("device é obrigatório")?),
    );
    job.insert(
        "characteristic".into(),
        json!(args
            .text("characteristic", 36)?
            .ok_or("characteristic é obrigatória")?),
    );
    job.insert("service".into(), json!(args.text("service", 36)?));
    Ok(job)
}

/// Some(reply) for lab tools, None for any other name.
pub(crate) fn call(id: Value, name: &str, arguments: &Value, client: &str) -> Option<Value> {
    let empty = Map::new();
    let args = Args(arguments.as_object().unwrap_or(&empty));
    let on_phone = |id: Value, kind: &'static str, job: Map<String, Value>, wait: u64| {
        crate::rpc::on_phone(id, kind, Value::Object(job), Duration::from_secs(wait))
    };
    let reply = (|| -> Result<Value, String> {
        Ok(match name {
            "lab_info" => {
                args.only(&[])?;
                on_phone(id.clone(), "lab.info", Map::new(), 10)
            }
            "lab_log" => {
                args.only(&["lines"])?;
                let lines = args.int("lines", 1..=300, 50)? as usize;
                let hub = hub::lock();
                let skip = hub.log.len().saturating_sub(lines);
                let text: Vec<&str> = hub.log.iter().skip(skip).map(String::as_str).collect();
                ok_text(
                    id.clone(),
                    if text.is_empty() {
                        "(log vazio)".into()
                    } else {
                        text.join("\n")
                    },
                    false,
                )
            }
            "ble_scan" => {
                args.only(&["seconds"])?;
                let seconds = args.int("seconds", 1..=15, 6)?;
                on_phone(
                    id.clone(),
                    "ble.scan",
                    Map::from_iter([("seconds".into(), json!(seconds))]),
                    seconds + 20,
                )
            }
            "ble_explore" => {
                args.only(&["device"])?;
                let device = args.text("device", 64)?.ok_or("device é obrigatório")?;
                on_phone(
                    id.clone(),
                    "ble.explore",
                    Map::from_iter([("device".into(), json!(device))]),
                    90,
                )
            }
            "ble_read" => {
                args.only(&["device", "characteristic", "service"])?;
                on_phone(id.clone(), "ble.read", target(&args)?, 45)
            }
            "ble_notify" => {
                args.only(&["device", "characteristic", "service", "seconds"])?;
                let seconds = args.int("seconds", 1..=60, 10)?;
                let mut job = target(&args)?;
                job.insert("seconds".into(), json!(seconds));
                on_phone(id.clone(), "ble.notify", job, seconds + 45)
            }
            "ble_write" => {
                args.only(&[
                    "device",
                    "characteristic",
                    "service",
                    "value_hex",
                    "response",
                ])?;
                let hex = args
                    .text("value_hex", 1024)?
                    .ok_or("value_hex é obrigatório")?;
                if hex.len() % 2 != 0 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return Err("value_hex precisa ter pares de dígitos hex".into());
                }
                let response = match args.0.get("response") {
                    None | Some(Value::Null) => true,
                    Some(Value::Bool(b)) => *b,
                    _ => return Err("response deve ser booleano".into()),
                };
                if !hub::write_allowed() {
                    return Ok(ok_text(id.clone(), "Escrita bloqueada. Chame lab_unlock_write e peça ao dono para aprovar no celular.".into(), true));
                }
                let mut job = target(&args)?;
                job.insert("value_hex".into(), json!(hex.to_ascii_lowercase()));
                job.insert("response".into(), json!(response));
                hub::log(&format!("ble_write por {client}: {} bytes", hex.len() / 2));
                on_phone(id.clone(), "ble.write", job, 45)
            }
            "huami_import" => {
                args.only(&["device", "auth_key", "apptoken", "userid"])?;
                let mut job = Map::new();
                let what = match (
                    args.text("auth_key", 40)?,
                    args.text("apptoken", 512)?,
                    args.text("userid", 32)?,
                ) {
                    (Some(key), None, None) => {
                        let key = key.trim_start_matches("0x").to_ascii_lowercase();
                        if key.len() != 32 || !key.bytes().all(|b| b.is_ascii_hexdigit()) {
                            return Err("auth_key precisa ter 32 dígitos hex".into());
                        }
                        job.insert(
                            "device".into(),
                            json!(args.text("device", 64)?.ok_or("auth_key exige device")?),
                        );
                        job.insert("auth_key".into(), json!(key));
                        "chave"
                    }
                    (None, Some(apptoken), Some(userid)) => {
                        if !userid.bytes().all(|b| b.is_ascii_digit()) {
                            return Err("userid deve ser numérico".into());
                        }
                        job.insert("apptoken".into(), json!(apptoken));
                        job.insert("userid".into(), json!(userid));
                        "sessão do Zepp"
                    }
                    _ => return Err("passe auth_key (com device) ou apptoken e userid".into()),
                };
                if !hub::write_allowed() {
                    return Ok(ok_text(id.clone(), "Importação bloqueada. Chame lab_unlock_write e peça ao dono para aprovar no celular.".into(), true));
                }
                hub::log(&format!("huami_import por {client}: {what}"));
                on_phone(id.clone(), "lab.huami_import", job, 40)
            }
            "lab_unlock_write" => {
                args.only(&["minutes"])?;
                let minutes = args.int("minutes", 1..=60, 30)?;
                let request =
                    hub::request_approval(client, 0, &format!("{minutes} min"), "write_unlock")
                        .map_err(str::to_owned)?;
                hub::log(&format!(
                    "{client} pediu liberação de escrita por {minutes} min"
                ));
                let who = client.to_owned();
                std::thread::spawn(move || match hub::wait_decision(request, UNLOCK_WAIT) {
                    Some(hub::Decision::Approve) => {
                        hub::lock().write_until =
                            Some(Instant::now() + Duration::from_secs(minutes * 60));
                        hub::log(&format!("escrita liberada por {minutes} min para {who}"));
                    }
                    Some(hub::Decision::Deny) => hub::log(&format!("escrita negada para {who}")),
                    None => hub::log(&format!("pedido de escrita de {who} expirou sem resposta")),
                });
                ok_text(
                    id.clone(),
                    format!(
                        "Pedido enviado ao celular (vale 10 min). Quando o dono aprovar, a escrita fica liberada por {minutes} min: confira write_allowed em lab_info ou o lab_log."
                    ),
                    false,
                )
            }
            _ => return Ok(Value::Null),
        })
    })();
    match reply {
        Ok(Value::Null) => None,
        Ok(reply) => Some(reply),
        Err(message) => Some(bad(id, &message)),
    }
}
