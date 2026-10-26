// SPDX-License-Identifier: AGPL-3.0-or-later
// AUTH wire commands: Gadgetbridge InitOperation2021, revision a0948ee1cbc2a870f91d313f8e37df5f524465f7.
// Upstream Copyright (C) 2021-2024 Andreas Shimokawa, José Rebelo.
//! Ordering/timeout gate bound to a Java crypto owner through JNI; no GATT adapter.
//! Never interprets peer acceptance as independent proof verification. No phase2,
//! time/profile writes, health reads or protected-operation dispatch.
use crate::huami_auth::{decode_reply, Challenge, Reply};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    AwaitChallenge,
    ComputeProof,
    AwaitProofWrite,
    AwaitResult,
    PeerAccepted,
    Closed,
}
#[derive(Debug, PartialEq, Eq)]
pub enum Fault {
    Stale,
    Clock,
    Timeout,
    Closed,
    Order,
    Envelope,
}
/// Command bytes only; no Debug/Serialize. Caller owns actual transmission and
/// crypto validation. Public keys/proofs in tests are synthetic/public fixtures.
pub struct Command {
    bytes: [u8; 52],
    len: usize,
}
impl Command {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.len]
    }
}
pub enum Event<'a> {
    ComputeProof(Challenge<'a>),
    PeerReportedSuccess,
    PeerRejected,
}
pub struct Session {
    generation: u64,
    last: u64,
    deadline: u64,
    stage: Stage,
}
impl Session {
    /// Public key must come from a reviewed provider. This function does NOT
    /// validate its curve membership or generate any randomness/private key.
    pub fn begin(generation: u64, now: u64, public: [u8; 48]) -> Result<(Self, Command), Fault> {
        let deadline = now.checked_add(15_000).ok_or(Fault::Clock)?;
        let mut bytes = [0; 52];
        bytes[..4].copy_from_slice(&[4, 2, 0, 2]);
        bytes[4..].copy_from_slice(&public);
        Ok((
            Self {
                generation,
                last: now,
                deadline,
                stage: Stage::AwaitChallenge,
            },
            Command { bytes, len: 52 },
        ))
    }
    pub fn stage(&self) -> Stage {
        self.stage
    }
    pub fn close(&mut self) {
        self.stage = Stage::Closed;
    }
    fn fail<T>(&mut self, e: Fault) -> Result<T, Fault> {
        self.close();
        Err(e)
    }
    fn check(&mut self, generation: u64, now: u64) -> Result<(), Fault> {
        if generation != self.generation {
            return Err(Fault::Stale);
        }
        if self.stage == Stage::Closed {
            return Err(Fault::Closed);
        }
        if now < self.last {
            return self.fail(Fault::Clock);
        }
        self.last = now;
        if now >= self.deadline {
            return self.fail(Fault::Timeout);
        }
        Ok(())
    }
    pub fn tick(&mut self, now: u64) -> Result<(), Fault> {
        self.check(self.generation, now)
    }
    pub fn receive<'a>(
        &mut self,
        generation: u64,
        now: u64,
        endpoint: u16,
        payload: &'a [u8],
    ) -> Result<Event<'a>, Fault> {
        self.check(generation, now)?;
        let reply = match decode_reply(endpoint, payload) {
            Ok(v) => v,
            Err(_) => return self.fail(Fault::Envelope),
        };
        match (self.stage, reply) {
            (Stage::AwaitChallenge, Reply::Challenge(c)) => {
                self.stage = Stage::ComputeProof;
                Ok(Event::ComputeProof(c))
            }
            (Stage::AwaitResult, Reply::PeerReportedSuccess) => {
                self.stage = Stage::PeerAccepted;
                Ok(Event::PeerReportedSuccess)
            }
            (Stage::AwaitResult, Reply::PeerReportedBadKey) => {
                self.close();
                Ok(Event::PeerRejected)
            }
            _ => self.fail(Fault::Order),
        }
    }
    /// Provider result for this generation/challenge only. Supplying arbitrary
    /// bytes proves nothing. A production provider is still an explicit gate.
    pub fn proof_prepared(
        &mut self,
        generation: u64,
        now: u64,
        proof: [u8; 32],
    ) -> Result<Command, Fault> {
        self.check(generation, now)?;
        if self.stage != Stage::ComputeProof {
            return self.fail(Fault::Order);
        }
        let mut bytes = [0; 52];
        bytes[0] = 5;
        bytes[1..33].copy_from_slice(&proof);
        self.stage = Stage::AwaitProofWrite;
        Ok(Command { bytes, len: 33 })
    }
    /// GATT write completion, not merely queued transmission. A future adapter
    /// must bind this to the actual write/generation and fail closed on error.
    pub fn proof_written(&mut self, generation: u64, now: u64) -> Result<(), Fault> {
        self.check(generation, now)?;
        if self.stage != Stage::AwaitProofWrite {
            return self.fail(Fault::Order);
        }
        self.stage = Stage::AwaitResult;
        Ok(())
    }
}
