//! Ephemeral, bearer-token-owned shell requests. No shell executes in Rust:
//! the Android app must show a local Activity confirmation and run in an
//! isolated Android UID. All control operations are JNI-only.
use crate::hub;
use serde_json::{json, Value};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const APPROVAL_TTL: Duration = Duration::from_secs(120);
const RUN_TTL: Duration = Duration::from_secs(20);
const KEEP_RESULT: Duration = Duration::from_secs(600);
const MAX_PENDING: usize = 4;
const MAX_RETAINED: usize = 16;
const MAX_OUTPUT: usize = 8192;

struct Request {
    id: u64,
    owner: String, // bearer token, NEVER returned through JNI / logs / MCP
    client: String,
    command: String,
    timeout_ms: u64,
    created: Instant,
    changed: Instant,
    state: &'static str,
    result: Option<Value>,
}
struct State {
    enabled: bool,
    jobs: Vec<Request>,
}
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
fn state() -> &'static Mutex<State> {
    STATE.get_or_init(|| {
        Mutex::new(State {
            enabled: false,
            jobs: Vec::new(),
        })
    })
}
fn lock() -> std::sync::MutexGuard<'static, State> {
    state().lock().unwrap_or_else(|p| p.into_inner())
}
fn terminal(state: &str) -> bool {
    matches!(state, "finished" | "rejected" | "expired" | "failed")
}
fn sweep(s: &mut State) {
    let now = Instant::now();
    for j in &mut s.jobs {
        if (matches!(j.state, "pending_approval" | "approved")
            && now.duration_since(j.created) >= APPROVAL_TTL)
            || (j.state == "running" && now.duration_since(j.changed) >= RUN_TTL)
        {
            j.state = if j.state == "running" {
                "failed"
            } else {
                "expired"
            };
            j.changed = now;
            j.result = Some(json!({"error":"timeout"}));
        }
    }
    s.jobs
        .retain(|j| !terminal(j.state) || now.duration_since(j.changed) < KEEP_RESULT);
    if s.jobs.len() > MAX_RETAINED {
        let excess = s.jobs.len() - MAX_RETAINED;
        // Only evict terminal records; do not lose pending owner decisions.
        let mut remaining = excess;
        s.jobs.retain(|j| {
            if remaining > 0 && terminal(j.state) {
                remaining -= 1;
                false
            } else {
                true
            }
        });
    }
}
pub(crate) fn submit(
    owner: &str,
    client: &str,
    command: &str,
    timeout_ms: u64,
) -> Result<u64, &'static str> {
    let mut s = lock();
    sweep(&mut s);
    if !s.enabled {
        return Err("shell_unverified");
    }
    if s.jobs.iter().filter(|j| !terminal(j.state)).count() >= MAX_PENDING {
        return Err("shell_busy");
    }
    let mut bytes = [0_u8; 8];
    hub::random(&mut bytes).map_err(|_| "random_unavailable")?;
    let id = u64::from_le_bytes(bytes) & (i64::MAX as u64);
    if id == 0 || s.jobs.iter().any(|j| j.id == id) {
        return Err("retry_request");
    }
    s.jobs.push(Request {
        id,
        owner: owner.to_owned(),
        client: client.to_owned(),
        command: command.to_owned(),
        timeout_ms,
        created: Instant::now(),
        changed: Instant::now(),
        state: "pending_approval",
        result: None,
    });
    Ok(id)
}
pub(crate) fn result(owner: &str, id: u64) -> Result<Value, &'static str> {
    let mut s = lock();
    sweep(&mut s);
    let j = s
        .jobs
        .iter()
        .find(|j| j.id == id && j.owner == owner)
        .ok_or("shell_not_found")?;
    Ok(
        json!({"request_id":j.id.to_string(), "backend":"android-isolated", "state":j.state,
        "requested":true, "executed":j.state=="finished" || j.state=="running" || j.result.as_ref().is_some_and(|r| r["executed"]==true),
        "confirmed":j.state=="finished", "result":j.result}),
    )
}
pub(crate) fn control(
    op: &str,
    arg: &serde_json::Map<String, Value>,
) -> Result<Value, &'static str> {
    let mut s = lock();
    sweep(&mut s);
    match op {
        "shell_enable" => {
            // Called ONLY by the local app after its on-screen probe checks UID
            // and private-file denial. Reset on MCP stop/process restart.
            s.enabled = arg
                .get("enabled")
                .and_then(Value::as_bool)
                .ok_or("invalid_request")?;
            Ok(json!({"ok":true,"enabled":s.enabled}))
        }
        "shell_pending" => Ok(
            json!({"enabled":s.enabled,"requests":s.jobs.iter().filter(|j|j.state=="pending_approval")
            .map(|j|json!({"id":j.id,"client":j.client,"command":j.command,"timeout_ms":j.timeout_ms})).collect::<Vec<_>>() }),
        ),
        "shell_decide" => {
            let id = arg
                .get("id")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?;
            let approved = arg
                .get("approve")
                .and_then(Value::as_bool)
                .ok_or("invalid_request")?;
            let enabled = s.enabled;
            let j = s
                .jobs
                .iter_mut()
                .find(|j| j.id == id && j.state == "pending_approval")
                .ok_or("unknown_request")?;
            j.state = if approved && enabled {
                "approved"
            } else {
                "rejected"
            };
            j.changed = Instant::now();
            Ok(json!({"ok":true,"state":j.state}))
        }
        "shell_take" => {
            if !s.enabled {
                return Ok(json!({"jobs":[]}));
            }
            let busy = s.jobs.iter().any(|j| j.state == "running");
            let jobs = if busy {
                vec![]
            } else {
                s.jobs
                    .iter_mut()
                    .filter(|j| j.state == "approved")
                    .take(1)
                    .map(|j| {
                        j.state = "running";
                        j.changed = Instant::now();
                        json!({"id":j.id,"command":j.command,"timeout_ms":j.timeout_ms})
                    })
                    .collect::<Vec<_>>()
            };
            Ok(json!({"jobs":jobs}))
        }
        "shell_complete" => {
            let id = arg
                .get("id")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?;
            let result = arg
                .get("result")
                .and_then(Value::as_object)
                .ok_or("invalid_request")?;
            let allowed = [
                "stdout",
                "stderr",
                "exit_code",
                "truncated",
                "executed",
                "timed_out",
                "uid",
                "isolated",
                "error",
            ];
            if result.keys().any(|k| !allowed.contains(&k.as_str())) {
                return Err("invalid_request");
            }
            for field in ["stdout", "stderr", "error"] {
                if result
                    .get(field)
                    .and_then(Value::as_str)
                    .is_some_and(|t| t.len() > MAX_OUTPUT)
                {
                    return Err("output_too_large");
                }
            }
            let j = s
                .jobs
                .iter_mut()
                .find(|j| j.id == id && j.state == "running")
                .ok_or("unknown_job")?;
            j.state = if result.get("executed") == Some(&Value::Bool(true))
                && result.get("exit_code").and_then(Value::as_i64).is_some()
                && result.get("isolated") == Some(&Value::Bool(true))
                && result.get("uid").and_then(Value::as_u64).is_some()
                && result.get("timed_out") != Some(&Value::Bool(true))
            {
                "finished"
            } else {
                "failed"
            };
            j.changed = Instant::now();
            j.result = Some(Value::Object(result.clone()));
            Ok(json!({"ok":true}))
        }
        _ => Err("unknown_op"),
    }
}
pub(crate) fn disable() {
    let mut s = lock();
    s.enabled = false;
    for j in &mut s.jobs {
        if !terminal(j.state) {
            j.state = "failed";
            j.result = Some(json!({"error":"server_stopped"}));
            j.changed = Instant::now();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_isolation_approval_denial_and_completion() {
        disable();
        assert_eq!(
            submit("owner", "client", "id", 5000).unwrap_err(),
            "shell_unverified"
        );
        control(
            "shell_enable",
            &json!({"enabled":true}).as_object().unwrap().clone(),
        )
        .unwrap();
        let id = submit("secret1", "client", "id", 5000).unwrap();
        assert_eq!(result("secret2", id).unwrap_err(), "shell_not_found");
        assert_eq!(result("secret1", id).unwrap()["state"], "pending_approval");
        control(
            "shell_decide",
            &json!({"id":id,"approve":false})
                .as_object()
                .unwrap()
                .clone(),
        )
        .unwrap();
        assert_eq!(result("secret1", id).unwrap()["state"], "rejected");
        let id2 = submit("secret1", "client", "pwd", 5000).unwrap();
        control(
            "shell_decide",
            &json!({"id":id2,"approve":true})
                .as_object()
                .unwrap()
                .clone(),
        )
        .unwrap();
        let taken = control("shell_take", &serde_json::Map::new()).unwrap();
        assert_eq!(taken["jobs"][0]["command"], "pwd");
        control("shell_complete",&json!({"id":id2,"result":{"executed":true,"exit_code":0,"uid":99001,"isolated":true,"stdout":"/","stderr":"","truncated":false}}).as_object().unwrap().clone()).unwrap();
        assert_eq!(result("secret1", id2).unwrap()["state"], "finished");
        disable();
    }
}
