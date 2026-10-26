// SPDX-License-Identifier: AGPL-3.0-or-later
//! Bounded process-local handles for the real handshake state machine. Holds
//! metadata/public peer fields/encrypted proof only, never credential/key bytes.
//! Not a BLE protocol, crypto provider or protected-operation authorization API.
use crate::huami_handshake::{Event, Fault, Session, Stage};
use std::{
    collections::BTreeMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Mutex, OnceLock},
};
#[repr(C)]
#[derive(Default)]
pub struct AuthResult {
    pub status: i32,
    pub stage: u32,
    pub handle: u64,
    pub written: usize,
    pub ack: u32,
}
struct Entry {
    generation: u64,
    session: Session,
    receiver: crate::huami_chunk::Receiver,
    tx: u16,
}

#[derive(Default)]
struct Pool {
    next: u64,
    sessions: BTreeMap<u64, Entry>,
}
static POOL: OnceLock<Mutex<Pool>> = OnceLock::new();
fn stage(s: Stage) -> u32 {
    match s {
        Stage::AwaitChallenge => 1,
        Stage::ComputeProof => 2,
        Stage::AwaitProofWrite => 3,
        Stage::AwaitResult => 4,
        Stage::PeerAccepted => 5,
        Stage::Closed => 6,
    }
}
fn fault(f: Fault) -> i32 {
    match f {
        Fault::Stale => 1,
        Fault::Clock => 2,
        Fault::Timeout => 3,
        Fault::Closed => 4,
        Fault::Order => 5,
        Fault::Envelope => 6,
    }
}
fn execute(
    pool: &mut Pool,
    op: u32,
    h: u64,
    g: u64,
    now: u64,
    endpoint: u16,
    input: &[u8],
) -> (AuthResult, Vec<u8>) {
    let error = |status| {
        (
            AuthResult {
                status,
                stage: 6,
                ..AuthResult::default()
            },
            Vec::new(),
        )
    };
    if op == 0 {
        if h != 0 || input.len() != 48 || g == 0 {
            return error(8);
        }
        if pool.sessions.len() >= 16 {
            return error(10);
        }
        let next = match pool.next.checked_add(1) {
            Some(n) => n,
            None => return error(10),
        };
        let (session, command) = match Session::begin(g, now, input.try_into().unwrap()) {
            Ok(v) => v,
            Err(e) => return error(fault(e)),
        };
        pool.next = next;
        let data = command.as_bytes().to_vec();
        pool.sessions.insert(
            next,
            Entry {
                generation: g,
                session,
                receiver: crate::huami_chunk::Receiver::new(g),
                tx: 0,
            },
        );
        return (
            AuthResult {
                status: 0,
                stage: 1,
                handle: next,
                written: data.len(),
                ack: 0,
            },
            data,
        );
    }
    if op == 5 && h == 0 {
        return error(0);
    }
    let entry = match pool.sessions.get_mut(&h) {
        Some(v) => v,
        None => return error(1),
    };
    if entry.generation != g {
        return (
            AuthResult {
                status: 1,
                stage: stage(entry.session.stage()),
                handle: h,
                written: 0,
                ack: 0,
            },
            Vec::new(),
        );
    }
    let s = &mut entry.session;
    let mut data = Vec::new();
    let mut ack = 0;
    let action: Result<(), Fault> = match op {
        1 => s.receive(g, now, endpoint, input).map(|event| match event {
            Event::ComputeProof(c) => {
                data.extend_from_slice(c.nonce());
                data.extend_from_slice(c.unvalidated_public_key());
            }
            Event::PeerReportedSuccess | Event::PeerRejected => {}
        }),
        2 if input.len() == 32 => s
            .proof_prepared(g, now, input.try_into().unwrap())
            .map(|command| data.extend_from_slice(command.as_bytes())),
        3 if input.is_empty() => s.proof_written(g, now),
        4 if input.is_empty() => s
            .tick(now)
            .and_then(|()| entry.receiver.expire(now).map_err(|_| Fault::Timeout)),
        6 => s.tick(now).and_then(|()| {
            // Peer ACK is transport metadata, never AUTH success/write completion.
            if input.first() == Some(&4) {
                return if input.len() == 5 {
                    Ok(())
                } else {
                    Err(Fault::Envelope)
                };
            }
            let received = entry
                .receiver
                .receive(g, now, input)
                .map_err(|_| Fault::Envelope)?;
            if received.ack_requested {
                ack = 0x10000 | ((received.handle as u32) << 8) | received.sequence as u32;
            }
            if let Some(message) = received.message {
                if message.payload.len() > 510 {
                    return Err(Fault::Envelope);
                }
                data.extend_from_slice(&message.endpoint.to_le_bytes());
                data.extend_from_slice(&message.payload);
            }
            Ok(())
        }),
        7 => s.tick(now).and_then(|()| {
            let allowed = matches!(s.stage(), Stage::AwaitChallenge)
                && input.len() == 52
                && input[..4] == [4, 2, 0, 2]
                || matches!(s.stage(), Stage::AwaitProofWrite)
                    && input.len() == 33
                    && input[0] == 5;
            if !allowed || entry.tx >= 255 {
                return Err(Fault::Order);
            }
            entry.tx += 1;
            let frames = crate::huami_chunk::encode_plain(0x82, entry.tx as u8, 23, input)
                .map_err(|_| Fault::Envelope)?;
            for frame in frames {
                data.extend_from_slice(&(frame.len() as u16).to_le_bytes());
                data.extend(frame);
            }
            Ok(())
        }),
        5 if input.is_empty() => {
            s.close();
            Ok(())
        }
        _ => {
            s.close();
            Err(Fault::Order)
        }
    };
    let status = action.err().map(fault).unwrap_or(0);
    if status != 0 && status != 1 {
        s.close();
    }
    let state = stage(s.stage());
    let handle = if s.stage() == Stage::Closed {
        pool.sessions.remove(&h);
        0
    } else {
        h
    };
    (
        AuthResult {
            status,
            stage: state,
            handle,
            written: data.len(),
            ack,
        },
        data,
    )
}
/// Native bridge operations:0 begin(public48),1 reply(<=67),2 proof32,
/// 3 write-confirmed,4 tick,5 close,6 plaintext RX frame,7 AUTH TX framing.
/// Input/output <=512 bytes; no secret-key input. Internal JNI result is536 bytes.
/// Status:0 ok,1 stale,2 clock,3 timeout,4 closed,5 order,6 envelope,
/// 7 capacity,8 arguments,9 internal,10 handle limit. Stage1..6 as documented.
/// # Safety
/// Input/output/result nonnull pointers must be valid, aligned where applicable,
/// readable/writable for specified lengths and nonoverlapping. No pointer retained.
#[no_mangle]
pub unsafe extern "C" fn b7_auth_session(
    op: u32,
    h: u64,
    g: u64,
    now: u64,
    endpoint: u16,
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
    result: *mut AuthResult,
) -> i32 {
    if result.is_null() {
        return 8;
    }
    let mut r = AuthResult {
        status: 8,
        stage: 6,
        ..AuthResult::default()
    };
    if length <= 512 && (!input.is_null() || length == 0) && !output.is_null() && capacity >= 512 {
        let bytes = if length == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(input, length) }
        };
        let value = catch_unwind(AssertUnwindSafe(|| {
            let mut pool = POOL
                .get_or_init(|| Mutex::new(Pool::default()))
                .lock()
                .map_err(|_| ())?;
            Ok::<_, ()>(execute(&mut pool, op, h, g, now, endpoint, bytes))
        }));
        match value {
            Ok(Ok((value, data))) => {
                r = value;
                if !data.is_empty() {
                    unsafe { std::ptr::copy_nonoverlapping(data.as_ptr(), output, data.len()) }
                }
            }
            _ => r.status = 9,
        }
    } else if capacity < 512 {
        r.status = 7
    }
    let status = r.status;
    unsafe { result.write(r) };
    status
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_cancel_timeout_and_capacity_are_bounded() {
        let mut p = Pool::default();
        let (r, d) = execute(&mut p, 0, 0, 1, 0, 0, &[0; 48]);
        assert_eq!(d.len(), 52);
        let h = r.handle;
        assert_eq!(execute(&mut p, 5, h, 2, 0, 0, &[]).0.status, 1);
        assert_eq!(p.sessions.len(), 1);
        assert_eq!(execute(&mut p, 4, h, 1, 15000, 0, &[]).0.status, 3);
        assert!(p.sessions.is_empty());
        for g in 1..=16 {
            assert_eq!(execute(&mut p, 0, 0, g, 0, 0, &[0; 48]).0.status, 0)
        }
        assert_eq!(execute(&mut p, 0, 0, 17, 0, 0, &[0; 48]).0.status, 10);
        let keys: Vec<_> = p
            .sessions
            .iter()
            .map(|(&h, entry)| (h, entry.generation))
            .collect();
        for (h, g) in keys {
            execute(&mut p, 5, h, g, 0, 0, &[]);
        }
        assert!(p.sessions.is_empty());
    }
}
