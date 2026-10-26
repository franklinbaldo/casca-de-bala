use crate::{hub, rpc};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{IpAddr, TcpStream},
    time::Duration,
};

const MAX_HEAD: usize = 16 * 1024;
const MAX_BODY: usize = 64 * 1024;
pub(crate) const PAIR_WAIT: Duration = Duration::from_secs(120);

struct Request {
    method: String,
    path: String,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Request {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }
}

fn read(stream: &TcpStream) -> Result<Request, u16> {
    let mut reader = BufReader::new(stream.take((MAX_HEAD + MAX_BODY) as u64));
    let mut line = String::new();
    let mut head = 0;
    let mut next = |reader: &mut BufReader<_>, line: &mut String| -> Result<(), u16> {
        line.clear();
        let n = reader.read_line(line).map_err(|_| 400u16)?;
        head += n;
        if n == 0 || head > MAX_HEAD {
            return Err(431);
        }
        Ok(())
    };
    next(&mut reader, &mut line)?;
    let mut parts = line.trim_end().splitn(3, ' ');
    let (method, path) = match (parts.next(), parts.next(), parts.next()) {
        (Some(m), Some(p), Some(v)) if v.starts_with("HTTP/1.") => (m.to_owned(), p.to_owned()),
        _ => return Err(400),
    };
    let mut headers = Vec::new();
    loop {
        next(&mut reader, &mut line)?;
        let text = line.trim_end();
        if text.is_empty() {
            break;
        }
        let (name, value) = text.split_once(':').ok_or(400u16)?;
        headers.push((name.trim().to_ascii_lowercase(), value.trim().to_owned()));
    }
    let request = Request {
        method,
        path,
        headers,
        body: Vec::new(),
    };
    if request.header("transfer-encoding").is_some() {
        return Err(501);
    }
    let length = match request.header("content-length") {
        Some(v) => v.parse::<usize>().map_err(|_| 400u16)?,
        None => 0,
    };
    if length > MAX_BODY {
        return Err(413);
    }
    let mut body = vec![0; length];
    reader.read_exact(&mut body).map_err(|_| 400u16)?;
    Ok(Request { body, ..request })
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        202 => "Accepted",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        413 => "Payload Too Large",
        429 => "Too Many Requests",
        431 => "Request Header Fields Too Large",
        501 => "Not Implemented",
        503 => "Service Unavailable",
        _ => "Error",
    }
}

fn respond(mut stream: &TcpStream, status: u16, kind: &str, extra: &str, body: &[u8]) {
    let head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: {kind}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n{extra}\r\n",
        reason(status),
        body.len()
    );
    let _ = stream
        .write_all(head.as_bytes())
        .and_then(|_| stream.write_all(body))
        .and_then(|_| stream.flush());
}

fn respond_json(stream: &TcpStream, status: u16, extra: &str, value: &Value) {
    let body = serde_json::to_vec(value).unwrap_or_default();
    respond(stream, status, "application/json", extra, &body);
}

pub(crate) fn busy(stream: TcpStream) {
    respond_json(&stream, 503, "", &json!({"error": "busy"}));
}

pub(crate) fn handle(stream: TcpStream, peer: IpAddr) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(15)));
    let request = match read(&stream) {
        Ok(request) => request,
        Err(status) => return respond_json(&stream, status, "", &json!({"error": reason(status)})),
    };
    // MCP clients are not browsers. Any Origin means a web page reached the
    // port (e.g. DNS rebinding to localhost): refuse before touching state.
    if request.header("origin").is_some() {
        return respond_json(&stream, 403, "", &json!({"error": "origin_not_allowed"}));
    }
    match (request.method.as_str(), request.path.as_str()) {
        ("POST", "/pair") => pair(&stream, &request, peer),
        (_, "/mcp") => mcp(&stream, &request),
        ("GET", "/") => respond(
            &stream,
            200,
            "text/plain; charset=utf-8",
            "",
            "Casca de Bala MCP: POST /pair para parear, /mcp para o protocolo.\n".as_bytes(),
        ),
        _ => respond_json(&stream, 404, "", &json!({"error": "not_found"})),
    }
}

fn mcp(stream: &TcpStream, request: &Request) {
    if request.method != "POST" {
        // JSON responses only: no server-initiated SSE stream on GET.
        return respond_json(
            stream,
            405,
            "Allow: POST\r\n",
            &json!({"error": "method_not_allowed"}),
        );
    }
    let token = request
        .header("authorization")
        .and_then(|v| v.split_once(' '))
        .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("bearer"))
        .map(|(_, token)| token.trim());
    let Some(client) = token.and_then(|t| hub::lock().client_for(t)) else {
        return respond_json(
            stream,
            401,
            "WWW-Authenticate: Bearer realm=\"casca\"\r\n",
            &json!({"error": "unauthorized", "hint": "POST /pair e aprove no celular"}),
        );
    };
    let Ok(message) = serde_json::from_slice::<Value>(&request.body) else {
        return respond_json(
            stream,
            400,
            "",
            &rpc::error(Value::Null, -32700, "parse error"),
        );
    };
    match rpc::dispatch(&message, &client) {
        Some(reply) => respond_json(stream, 200, "", &reply),
        None => respond(stream, 202, "application/json", "", b""),
    }
}

fn valid_name(name: &str) -> bool {
    (1..=64).contains(&name.chars().count()) && !name.chars().any(char::is_control)
}

/// Streams the matching code first, then waits for the decision on the phone.
fn pair(stream: &TcpStream, request: &Request, peer: IpAddr) {
    let name = serde_json::from_slice::<Value>(&request.body)
        .ok()
        .and_then(|v| v["client"].as_str().map(str::to_owned));
    let Some(name) = name.filter(|n| valid_name(n)) else {
        return respond_json(
            stream,
            400,
            "",
            &json!({"error": "client name required", "example": {"client": "jatoba"}}),
        );
    };
    let mut byte = [0u8; 1];
    if hub::random(&mut byte).is_err() {
        return respond_json(stream, 503, "", &json!({"error": "random"}));
    }
    let code = 10 + byte[0] % 90;
    if hub::lock().clients.len() >= hub::MAX_CLIENTS {
        return respond_json(stream, 409, "", &json!({"error": "client_limit"}));
    }
    let id = match hub::request_approval(&name, code, &peer.to_string(), "pairing") {
        Ok(id) => id,
        Err(error) => return respond_json(stream, 429, "", &json!({"error": error})),
    };
    let mut out = stream;
    let opening = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\nConfirme no celular o código {code} para \"{name}\". Aguardando até {} s…\n",
        PAIR_WAIT.as_secs()
    );
    let _ = out.write_all(opening.as_bytes()).and_then(|_| out.flush());
    let line = match hub::wait_decision(id, PAIR_WAIT) {
        Some(hub::Decision::Approve) => match hub::new_token() {
            Ok(token) => {
                let mut hub = hub::lock();
                hub.clients.push(hub::Client {
                    token: token.clone(),
                    name: name.clone(),
                    created_ms: hub::now_ms(),
                });
                if hub.save().is_ok() {
                    drop(hub);
                    hub::log(&format!("pareamento aprovado: {name}"));
                    format!("Aprovado.\nCASCA_TOKEN={token}\n")
                } else {
                    hub.clients.pop();
                    "Erro ao salvar o token no celular.\n".to_owned()
                }
            }
            Err(_) => "Erro ao gerar o token.\n".to_owned(),
        },
        Some(hub::Decision::Deny) => "Negado no celular.\n".to_owned(),
        None => "Tempo esgotado sem resposta no celular.\n".to_owned(),
    };
    let _ = out.write_all(line.as_bytes()).and_then(|_| out.flush());
}
