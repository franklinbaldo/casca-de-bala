use band7_terminal::{activity_db::StoreError, ingestion::Source, sync_session::*};
use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "b7-session-fixture-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
    fn open(&self) -> SyncSession {
        SyncSession::open(
            &self.0.join("synthetic.sqlite"),
            Source {
                device_ref: "fixture".into(),
                firmware: "test".into(),
                synthetic: true,
            },
            120,
            240,
        )
        .unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn frame(flags: u8, sequence: u8, data: &[u8]) -> Vec<u8> {
    let mut bytes = vec![3, flags, 0, 7, sequence];
    if flags & 1 != 0 {
        bytes.extend_from_slice(&[16, 0, 0, 0, 0x34, 0x12]);
    }
    bytes.extend_from_slice(data);
    bytes
}
#[test]
fn framing_does_not_classify_activity_but_trusted_fixture_fetch_reaches_db() {
    let f = Fixture::new();
    let mut s = f.open();
    let g = s.generation();
    s.receive_plaintext(g, 0, &frame(1, 0, &[1; 8])).unwrap();
    assert!(s.progress().unwrap().transport_partial);
    let message = s
        .receive_plaintext(g, 1, &frame(6, 1, &[2; 8]))
        .unwrap()
        .message
        .unwrap();
    assert_eq!(message.endpoint, 0x1234);
    assert_eq!(s.progress().unwrap().stored, 0);
    // Explicit synthetic adapter: NOT a claim that endpoint 0x1234 is Activity8.
    s.enqueue_activity8(g, 120, Some(0), &message.payload)
        .unwrap();
    assert!(!s.progress().unwrap().window_ready);
    s.process_next(g).unwrap();
    assert!(s.progress().unwrap().window_ready);
    drop(s);
    let mut reopened = f.open();
    let p = reopened.progress().unwrap();
    assert_eq!((p.stored, p.missing, p.cursor), (2, 0, Some(240)));
}
#[test]
fn cancellation_mid_transport_and_between_commits_preserves_restart_cursor() {
    let f = Fixture::new();
    let mut s = f.open();
    let old = s.generation();
    let token = s.cancellation();
    s.enqueue_activity8(old, 120, None, &[1; 8]).unwrap();
    s.process_next(old).unwrap();
    s.receive_plaintext(old, 0, &frame(1, 0, &[1; 8])).unwrap();
    s.enqueue_activity8(old, 180, None, &[1; 8]).unwrap();
    token.request();
    let p = s.progress().unwrap();
    assert_eq!((p.stored, p.cursor, p.pending_batches), (1, Some(180), 0));
    assert!(p.paused);
    assert!(!p.transport_partial);
    assert!(!p.window_ready);
    let new = s.reconnect().unwrap();
    assert_ne!(new, old);
    assert!(matches!(
        s.receive_plaintext(old, 1, &frame(6, 1, &[1; 8])),
        Err(SyncError::StaleSession)
    ));
    assert_eq!(
        s.enqueue_activity8(old, 180, None, &[1; 8]),
        Err(SyncError::StaleSession)
    );
    token.request(); // A cancelled old token cannot cancel the new session.
    s.enqueue_activity8(new, 180, None, &[1; 8]).unwrap();
    s.process_next(new).unwrap();
    assert!(s.progress().unwrap().window_ready);
    drop(s);
    let mut reopened = f.open();
    assert_ne!(reopened.generation(), new);
    assert_eq!(reopened.progress().unwrap().cursor, Some(240));
}
#[test]
fn bounded_backpressure_keeps_input_available_and_never_skips_gap() {
    let f = Fixture::new();
    let mut s = f.open();
    let g = s.generation();
    let max = vec![1; BATCH_BYTES];
    s.enqueue_activity8(g, 120, None, &max).unwrap();
    s.enqueue_activity8(g, 120, None, &max).unwrap();
    assert_eq!(
        s.enqueue_activity8(g, 120, None, &[1; 8]),
        Err(SyncError::Backpressure)
    );
    let p = s.progress().unwrap();
    assert_eq!(
        (p.pending_batches, p.queued_bytes),
        (QUEUE_BATCHES, QUEUE_BATCHES * BATCH_BYTES)
    );
    assert_eq!(p.cursor, None);
    assert!(!p.window_ready);
    s.cancellation().request();
    assert_eq!(s.progress().unwrap().queued_bytes, 0);
    let g = s.reconnect().unwrap();
    assert_eq!(
        s.enqueue_activity8(g, 120, None, &vec![0; BATCH_BYTES + 8]),
        Err(SyncError::Length)
    );
    s.enqueue_activity8(g, 180, None, &[1; 8]).unwrap();
    s.enqueue_activity8(g, 180, None, &[1; 8]).unwrap();
    assert_eq!(s.process_next(g).unwrap().unwrap().inserted, 1);
    assert_eq!(s.process_next(g).unwrap().unwrap().inserted, 0);
    assert_eq!(s.progress().unwrap().cursor, Some(120));
    assert!(!s.progress().unwrap().window_ready);
    s.enqueue_activity8(g, 120, None, &[1; 8]).unwrap();
    s.process_next(g).unwrap();
    assert!(s.progress().unwrap().window_ready);
}
#[test]
fn conflict_stops_queue_rolls_back_and_requires_explicit_reconnect() {
    let f = Fixture::new();
    let mut s = f.open();
    let g = s.generation();
    s.enqueue_activity8(g, 180, None, &[1; 8]).unwrap();
    s.process_next(g).unwrap();
    s.enqueue_activity8(g, 120, None, &[2; 16]).unwrap();
    s.enqueue_activity8(g, 120, None, &[1; 8]).unwrap();
    assert!(matches!(
        s.process_next(g),
        Err(SyncError::Store(StoreError::Ingestion(_)))
    ));
    let p = s.progress().unwrap();
    assert_eq!((p.stored, p.cursor, p.pending_batches), (1, Some(120), 0));
    assert!(p.paused);
    assert_eq!(s.process_next(g), Err(SyncError::Cancelled));
    let g = s.reconnect().unwrap();
    s.enqueue_activity8(g, 120, None, &[1; 8]).unwrap();
    s.process_next(g).unwrap();
    assert!(s.progress().unwrap().window_ready);
}
#[test]
fn timeout_out_of_order_and_reconnect_never_write_partial_bytes() {
    let f = Fixture::new();
    let mut s = f.open();
    let g = s.generation();
    s.receive_plaintext(g, 0, &frame(1, 0, &[1; 8])).unwrap();
    assert!(s.expire_transport(g, 5000).is_err());
    assert!(s.receive_plaintext(g, 5001, &frame(6, 1, &[1; 8])).is_err());
    s.receive_plaintext(g, 5002, &frame(1, 0, &[1; 8])).unwrap();
    assert!(s.receive_plaintext(g, 5003, &frame(6, 2, &[1; 8])).is_err());
    assert_eq!(s.progress().unwrap().stored, 0);
    assert!(!s.progress().unwrap().window_ready);
}
