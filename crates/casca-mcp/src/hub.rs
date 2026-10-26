use crate::{http, peer::allowed};
use serde_json::{json, Map, Value};
use std::{
    collections::HashMap,
    fs,
    io::Read,
    net::{Ipv4Addr, Ipv6Addr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Condvar, Mutex, MutexGuard, OnceLock,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub(crate) const MAX_CLIENTS: usize = 32;
pub(crate) const MAX_PENDING: usize = 4;
const MAX_CONNECTIONS: usize = 16;
const TOKEN_FILE: &str = "tokens.json";

#[derive(Clone)]
pub(crate) struct Client {
    pub token: String,
    pub name: String,
    pub created_ms: u64,
}

pub(crate) struct Pending {
    pub id: u64,
    pub client: String,
    pub code: u8,
    pub peer: String,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Decision {
    Approve,
    Deny,
}

pub(crate) struct Hub {
    pub dir: Option<PathBuf>,
    pub clients: Vec<Client>,
    pub pending: Vec<Pending>,
    pub decided: HashMap<u64, Decision>,
    pub next_id: u64,
    pub snapshot: Option<(Value, u64)>,
    running: Option<(u16, Arc<AtomicBool>)>,
}

static HUB: OnceLock<(Mutex<Hub>, Condvar)> = OnceLock::new();

pub(crate) fn hub() -> &'static (Mutex<Hub>, Condvar) {
    HUB.get_or_init(|| {
        let hub = Hub {
            dir: None,
            clients: Vec::new(),
            pending: Vec::new(),
            decided: HashMap::new(),
            next_id: 1,
            snapshot: None,
            running: None,
        };
        (Mutex::new(hub), Condvar::new())
    })
}

pub(crate) fn lock() -> MutexGuard<'static, Hub> {
    hub()
        .0
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

pub(crate) fn random(buffer: &mut [u8]) -> std::io::Result<()> {
    fs::File::open("/dev/urandom")?.read_exact(buffer)
}

/// 32 random bytes, base64url: what a client sends as `Authorization: Bearer`.
pub(crate) fn new_token() -> std::io::Result<String> {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut bytes = [0u8; 32];
    random(&mut bytes)?;
    let mut token = String::from("casca_");
    for chunk in bytes.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |acc, (i, &b)| acc | u32::from(b) << (16 - 8 * i));
        for i in 0..=chunk.len() {
            token.push(ALPHABET[(n >> (18 - 6 * i)) as usize & 63] as char);
        }
    }
    Ok(token)
}

impl Hub {
    /// Constant-time per candidate, so response time does not leak a prefix.
    pub(crate) fn authorized(&self, presented: &str) -> bool {
        self.clients.iter().fold(false, |found, client| {
            let a = client.token.as_bytes();
            let b = presented.as_bytes();
            let same =
                a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0;
            found | same
        })
    }

    pub(crate) fn save(&self) -> std::io::Result<()> {
        let dir = self.dir.as_ref().ok_or(std::io::ErrorKind::NotFound)?;
        let clients: Vec<Value> = self
            .clients
            .iter()
            .map(|c| json!({"token": c.token, "name": c.name, "created_unix_ms": c.created_ms}))
            .collect();
        let body = serde_json::to_vec_pretty(&json!({"version": 1, "clients": clients}))?;
        let temporary = dir.join("tokens.json.tmp");
        fs::write(&temporary, body)?;
        fs::rename(temporary, dir.join(TOKEN_FILE))
    }
}

fn load(dir: &Path) -> Result<Vec<Client>, &'static str> {
    let bytes = match fs::read(dir.join(TOKEN_FILE)) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(_) => return Err("token_store"),
    };
    let value: Value = serde_json::from_slice(&bytes).map_err(|_| "token_store")?;
    let list = value["clients"].as_array().ok_or("token_store")?;
    list.iter()
        .take(MAX_CLIENTS)
        .map(|c| {
            Ok(Client {
                token: c["token"]
                    .as_str()
                    .filter(|t| t.starts_with("casca_"))
                    .ok_or("token_store")?
                    .to_owned(),
                name: c["name"].as_str().ok_or("token_store")?.to_owned(),
                created_ms: c["created_unix_ms"].as_u64().unwrap_or(0),
            })
        })
        .collect()
}

fn bind(port: u16) -> std::io::Result<TcpListener> {
    // Dual-stack where available; peers are filtered per connection anyway.
    TcpListener::bind((Ipv6Addr::UNSPECIFIED, port))
        .or_else(|_| TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)))
}

fn serve(listener: TcpListener, stop: Arc<AtomicBool>) {
    let active = Arc::new(AtomicUsize::new(0));
    for stream in listener.incoming() {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        let Ok(stream) = stream else {
            thread::sleep(Duration::from_millis(50));
            continue;
        };
        let Ok(peer) = stream.peer_addr() else {
            continue;
        };
        if !allowed(peer.ip()) {
            continue;
        }
        if active.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
            active.fetch_sub(1, Ordering::SeqCst);
            http::busy(stream);
            continue;
        }
        let active = Arc::clone(&active);
        thread::spawn(move || {
            http::handle(stream, peer.ip().to_canonical());
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
}

/// JSON control surface for the app (via `b7_mcp_json`): start, stop, status,
/// decide, publish, forget. Always returns a JSON object; failures carry `error`.
pub fn control(input: &[u8]) -> Vec<u8> {
    let reply = serde_json::from_slice::<Map<String, Value>>(input)
        .map_err(|_| "invalid_json")
        .and_then(|request| execute(&request));
    let value = reply.unwrap_or_else(|error| json!({"error": error}));
    serde_json::to_vec(&value).unwrap_or_else(|_| br#"{"error":"output"}"#.to_vec())
}

fn execute(request: &Map<String, Value>) -> Result<Value, &'static str> {
    match request.get("op").and_then(Value::as_str) {
        Some("start") => start(request),
        Some("stop") => {
            stop();
            Ok(json!({"ok": true}))
        }
        Some("status") => Ok(status()),
        Some("decide") => {
            let id = request
                .get("id")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?;
            let approve = request
                .get("approve")
                .and_then(Value::as_bool)
                .ok_or("invalid_request")?;
            let mut hub = lock();
            if !hub.pending.iter().any(|p| p.id == id) {
                return Err("unknown_request");
            }
            hub.decided.insert(
                id,
                if approve {
                    Decision::Approve
                } else {
                    Decision::Deny
                },
            );
            notify();
            Ok(json!({"ok": true}))
        }
        Some("publish") => {
            // The reply envelope of the GATT bridge (`cached_snapshot`), never raw GATT.
            let reply = request
                .get("snapshot")
                .and_then(Value::as_object)
                .ok_or("invalid_request")?;
            if reply.contains_key("error") {
                return Err("snapshot_error");
            }
            let result = reply
                .get("result")
                .filter(|r| r.is_object())
                .ok_or("invalid_request")?;
            lock().snapshot = Some((result.clone(), now_ms()));
            Ok(json!({"ok": true}))
        }
        Some("forget") => {
            let name = request
                .get("client")
                .and_then(Value::as_str)
                .ok_or("invalid_request")?;
            let mut hub = lock();
            let before = hub.clients.len();
            hub.clients.retain(|c| c.name != name);
            let removed = before - hub.clients.len();
            if removed > 0 {
                hub.save().map_err(|_| "token_store")?;
            }
            Ok(json!({"ok": true, "removed": removed}))
        }
        _ => Err("unknown_op"),
    }
}

/// Wakes pairing requests waiting for a decision.
pub(crate) fn notify() {
    hub().1.notify_all();
}

fn start(request: &Map<String, Value>) -> Result<Value, &'static str> {
    let dir = request
        .get("dir")
        .and_then(Value::as_str)
        .filter(|d| !d.is_empty() && d.len() <= 4096)
        .ok_or("invalid_request")?;
    let port = request
        .get("port")
        .and_then(Value::as_u64)
        .filter(|&p| p == 0 || (1024..=65535).contains(&p))
        .ok_or("invalid_request")? as u16;
    let mut hub = lock();
    if hub.running.is_some() {
        return Err("already_running");
    }
    let dir = PathBuf::from(dir);
    fs::create_dir_all(&dir).map_err(|_| "token_store")?;
    hub.clients = load(&dir)?;
    hub.dir = Some(dir);
    let listener = bind(port).map_err(|_| "bind")?;
    let port = listener.local_addr().map_err(|_| "bind")?.port();
    let flag = Arc::new(AtomicBool::new(false));
    let serving = Arc::clone(&flag);
    thread::Builder::new()
        .name("casca-mcp".into())
        .spawn(move || serve(listener, serving))
        .map_err(|_| "thread")?;
    hub.running = Some((port, flag));
    Ok(json!({"ok": true, "port": port}))
}

fn stop() {
    let mut hub = lock();
    if let Some((port, flag)) = hub.running.take() {
        flag.store(true, Ordering::SeqCst);
        // Wake the blocking accept so the listener thread exits and frees the port.
        let _ = TcpStream::connect((Ipv4Addr::LOCALHOST, port));
    }
    let ids: Vec<u64> = hub.pending.iter().map(|p| p.id).collect();
    for id in ids {
        hub.decided.insert(id, Decision::Deny);
    }
    notify();
}

fn status() -> Value {
    let hub = lock();
    json!({
        "running": hub.running.is_some(),
        "port": hub.running.as_ref().map(|(port, _)| *port),
        "clients": hub.clients.iter().map(|c| json!({"name": c.name, "created_unix_ms": c.created_ms})).collect::<Vec<_>>(),
        "pending": hub.pending.iter().map(|p| json!({"id": p.id, "client": p.client, "code": p.code, "peer": p.peer})).collect::<Vec<_>>(),
        "snapshot_at_unix_ms": hub.snapshot.as_ref().map(|(_, at)| *at),
    })
}
