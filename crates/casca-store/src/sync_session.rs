// SPDX-License-Identifier: AGPL-3.0-or-later
//! Host sync coordinator. Activity8 admission is an explicit trusted fetch boundary,
//! not a mapping from a Huami endpoint to historical records.
use crate::activity_db::{ActivityDb, Committed, StoreError};
use casca_core::{
    huami_chunk::{Received, Receiver, RxError},
    ingestion::{Source, MAX_RECORDS},
};
use std::{
    collections::VecDeque,
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
};
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);
fn next_generation() -> Result<u64, SyncError> {
    NEXT_GENERATION
        .try_update(Ordering::SeqCst, Ordering::SeqCst, |v| v.checked_add(1))
        .map_err(|_| SyncError::Generation)
}
pub const QUEUE_BATCHES: usize = 2;
pub const BATCH_BYTES: usize = MAX_RECORDS * 8;
#[derive(Clone)]
pub struct Cancellation(Arc<AtomicBool>);
impl Cancellation {
    pub fn request(&self) {
        self.0.store(true, Ordering::SeqCst);
    }
    pub fn requested(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}
#[derive(Debug, PartialEq, Eq)]
pub enum SyncError {
    StaleSession,
    Cancelled,
    Backpressure,
    Length,
    Generation,
    Transport(RxError),
    Store(StoreError),
}
struct Batch {
    first: i64,
    offset: Option<i32>,
    bytes: Vec<u8>,
}
#[derive(Debug, PartialEq, Eq)]
pub struct Progress {
    pub cursor: Option<i64>,
    pub stored: usize,
    pub missing: usize,
    pub pending_batches: usize,
    pub queued_bytes: usize,
    pub transport_partial: bool,
    pub paused: bool,
    /// Only this expected DB window; never means full device/fetch completion.
    pub window_ready: bool,
}
pub struct SyncSession {
    db: ActivityDb,
    receiver: Receiver,
    generation: u64,
    cancel: Cancellation,
    queue: VecDeque<Batch>,
}
impl SyncSession {
    pub fn open(path: &Path, source: Source, start: i64, end: i64) -> Result<Self, SyncError> {
        let generation = next_generation()?;
        Ok(Self {
            db: ActivityDb::open(path, source, start, end).map_err(SyncError::Store)?,
            receiver: Receiver::new(generation),
            generation,
            cancel: Cancellation(Arc::new(AtomicBool::new(false))),
            queue: VecDeque::new(),
        })
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn cancellation(&self) -> Cancellation {
        self.cancel.clone()
    }
    fn check(&mut self, generation: u64) -> Result<(), SyncError> {
        if generation != self.generation {
            return Err(SyncError::StaleSession);
        }
        if self.cancel.requested() {
            self.queue.clear();
            self.receiver = Receiver::new(self.generation);
            return Err(SyncError::Cancelled);
        }
        Ok(())
    }
    /// Plaintext framing inspection only; no returned endpoint is auto-classified.
    /// Caller must not retain an unbounded list of returned messages.
    pub fn receive_plaintext(
        &mut self,
        generation: u64,
        now: u64,
        frame: &[u8],
    ) -> Result<Received, SyncError> {
        self.check(generation)?;
        self.receiver
            .receive(generation, now, frame)
            .map_err(SyncError::Transport)
    }
    pub fn expire_transport(&mut self, generation: u64, now: u64) -> Result<(), SyncError> {
        self.check(generation)?;
        self.receiver.expire(now).map_err(SyncError::Transport)
    }
    /// Caller has already authenticated/decrypted AND selected an Activity8 fetch
    /// with a resolved timestamp. Queue is a host API, not a new wire envelope.
    /// On backpressure input is not copied/consumed; caller retains responsibility.
    pub fn enqueue_activity8(
        &mut self,
        generation: u64,
        first: i64,
        offset: Option<i32>,
        bytes: &[u8],
    ) -> Result<(), SyncError> {
        self.check(generation)?;
        if bytes.len() > BATCH_BYTES || !bytes.len().is_multiple_of(8) {
            return Err(SyncError::Length);
        }
        if self.queue.len() == QUEUE_BATCHES {
            return Err(SyncError::Backpressure);
        }
        self.queue.push_back(Batch {
            first,
            offset,
            bytes: bytes.to_vec(),
        });
        Ok(())
    }
    pub fn process_next(&mut self, generation: u64) -> Result<Option<Committed>, SyncError> {
        self.process_with_check(generation, || {})
    }
    fn process_with_check(
        &mut self,
        generation: u64,
        at_commit: impl FnOnce(),
    ) -> Result<Option<Committed>, SyncError> {
        self.check(generation)?;
        let Some(batch) = self.queue.pop_front() else {
            return Ok(None);
        };
        let token = self.cancel.clone();
        let result = self
            .db
            .ingest_checked(batch.first, batch.offset, &batch.bytes, || {
                at_commit();
                !token.requested()
            });
        match result {
            Ok(committed) => Ok(Some(committed)),
            Err(error) => {
                self.cancel.request();
                self.queue.clear();
                self.receiver = Receiver::new(self.generation);
                if error == StoreError::Cancelled {
                    Err(SyncError::Cancelled)
                } else {
                    Err(SyncError::Store(error))
                }
            }
        }
    }
    /// Reconnection drops uncommitted work, invalidates tokens/callback generation,
    /// preserves SQLite and requires the fetch layer to replay from DB coverage.
    pub fn reconnect(&mut self) -> Result<u64, SyncError> {
        let next = next_generation()?;
        self.cancel.request();
        self.cancel = Cancellation(Arc::new(AtomicBool::new(false)));
        self.queue.clear();
        self.generation = next;
        self.receiver = Receiver::new(next);
        Ok(next)
    }
    pub fn progress(&mut self) -> Result<Progress, SyncError> {
        if self.cancel.requested() {
            self.queue.clear();
            self.receiver = Receiver::new(self.generation);
        }
        let (cursor, stored, missing) = self.db.coverage_snapshot().map_err(SyncError::Store)?;
        let transport_partial = self.receiver.has_partial();
        Ok(Progress {
            cursor,
            stored,
            missing,
            pending_batches: self.queue.len(),
            queued_bytes: self.queue.iter().map(|b| b.bytes.len()).sum(),
            transport_partial,
            paused: self.cancel.requested(),
            window_ready: missing == 0
                && self.queue.is_empty()
                && !transport_partial
                && !self.cancel.requested(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancellation_after_sql_staging_rolls_back_and_reopen_resumes() {
        let directory = std::env::temp_dir().join(format!("b7-sync-cancel-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("synthetic.sqlite");
        let source = Source {
            device_ref: "fixture".into(),
            firmware: "test".into(),
            synthetic: true,
        };
        let mut session = SyncSession::open(&path, source.clone(), 120, 240).unwrap();
        let generation = session.generation();
        let token = session.cancellation();
        session
            .enqueue_activity8(generation, 120, None, &[1; 16])
            .unwrap();
        assert_eq!(
            session.process_with_check(generation, || {
                std::thread::spawn(move || token.request()).join().unwrap();
            }),
            Err(SyncError::Cancelled)
        );
        assert_eq!(session.progress().unwrap().stored, 0);
        drop(session);
        let mut next = SyncSession::open(&path, source, 120, 240).unwrap();
        assert_eq!(next.progress().unwrap().cursor, None);
        next.enqueue_activity8(next.generation(), 120, None, &[1; 16])
            .unwrap();
        next.process_next(next.generation()).unwrap();
        assert!(next.progress().unwrap().window_ready);
        drop(next);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
