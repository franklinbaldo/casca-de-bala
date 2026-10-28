// SPDX-License-Identifier: AGPL-3.0-or-later
// Protocol reference: Gadgetbridge Huami2021Chunked{Encoder,Decoder},
// Copyright (C) 2022-2024 José Rebelo. Rust implementation and stricter
// validation: workspace contributors, 2026. See docs/huami-transport.md.
//! Receive-only, extended Huami2021 plaintext framing. No GATT or authentication.

pub const MAX_MESSAGE: usize = 4096;
pub const MAX_FRAGMENT: usize = 512;
pub const TIMEOUT_MS: u64 = 5000;

#[derive(Debug, PartialEq, Eq)]
pub enum RxError {
    Session,
    Clock,
    Timeout,
    Header,
    EncryptedUnsupported,
    Length,
    Sequence,
    Handle,
}
#[derive(Debug, PartialEq, Eq)]
pub struct Message {
    pub endpoint: u16,
    pub payload: Vec<u8>,
}
#[derive(Debug, PartialEq, Eq)]
pub struct Received {
    pub handle: u8,
    pub sequence: u8,
    pub ack_requested: bool,
    pub message: Option<Message>,
}
#[derive(Debug)]
struct Partial {
    handle: u8,
    next: u16,
    endpoint: u16,
    length: usize,
    deadline: u64,
    payload: Vec<u8>,
}
#[derive(Debug)]
pub struct Receiver {
    session: u64,
    last_time: u64,
    partial: Option<Partial>,
}
impl Receiver {
    /// Host must use a fresh generation on every connection, including failure.
    pub fn new(session: u64) -> Self {
        Self {
            session,
            last_time: 0,
            partial: None,
        }
    }
    pub fn has_partial(&self) -> bool {
        self.partial.is_some()
    }
    /// Clears an incomplete message even if no more notifications arrive.
    pub fn expire(&mut self, now: u64) -> Result<(), RxError> {
        if now < self.last_time {
            self.partial = None;
            return Err(RxError::Clock);
        }
        self.last_time = now;
        if self.partial.as_ref().is_some_and(|p| now >= p.deadline) {
            self.partial = None;
            return Err(RxError::Timeout);
        }
        Ok(())
    }
    /// Any malformed notification resets reassembly; no implicit retry/ACK IO.
    pub fn receive(&mut self, session: u64, now: u64, bytes: &[u8]) -> Result<Received, RxError> {
        // Stale callbacks must not destroy the current session's message.
        if session != self.session {
            return Err(RxError::Session);
        }
        let result = self.receive_inner(now, bytes);
        if result.is_err() {
            self.partial = None;
        }
        result
    }
    fn receive_inner(&mut self, now: u64, bytes: &[u8]) -> Result<Received, RxError> {
        self.expire(now)?;
        if bytes.len() < 5
            || bytes.len() > MAX_FRAGMENT
            || bytes[0] != 3
            || bytes[1] & !0x0f != 0
            || bytes[2] != 0
        {
            return Err(RxError::Header);
        }
        let flags = bytes[1];
        if flags & 8 != 0 {
            return Err(RxError::EncryptedUnsupported);
        }
        let first = flags & 1 != 0;
        let last = flags & 2 != 0;
        let handle = bytes[3];
        let sequence = bytes[4];
        let offset = if first { 11 } else { 5 };
        if bytes.len() <= offset {
            return Err(RxError::Length);
        }
        if first {
            if self.partial.is_some() || sequence != 0 {
                return Err(RxError::Sequence);
            }
            let length = u32::from_le_bytes(bytes[5..9].try_into().unwrap()) as usize;
            if length == 0 || length > MAX_MESSAGE {
                return Err(RxError::Length);
            }
            let deadline = now.checked_add(TIMEOUT_MS).ok_or(RxError::Clock)?;
            self.partial = Some(Partial {
                handle,
                next: 0,
                endpoint: u16::from_le_bytes([bytes[9], bytes[10]]),
                length,
                deadline,
                payload: Vec::with_capacity(length),
            });
        }
        let p = self.partial.as_mut().ok_or(RxError::Sequence)?;
        if handle != p.handle {
            return Err(RxError::Handle);
        }
        if u16::from(sequence) != p.next {
            return Err(RxError::Sequence);
        }
        let next_len = p.payload.len() + bytes.len() - offset;
        if next_len > p.length
            || (last && next_len != p.length)
            || (!last && (next_len == p.length || sequence == 255))
        {
            return Err(RxError::Length);
        }
        p.payload.extend_from_slice(&bytes[offset..]);
        p.next += 1;
        let message = if last {
            let p = self.partial.take().unwrap();
            Some(Message {
                endpoint: p.endpoint,
                payload: p.payload,
            })
        } else {
            None
        };
        Ok(Received {
            handle,
            sequence,
            ack_requested: flags & 4 != 0,
            message,
        })
    }
}

#[cfg(test)]
mod tests;
