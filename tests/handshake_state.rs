use band7_terminal::huami_auth::AUTH_ENDPOINT;
use band7_terminal::huami_handshake::{Event, Fault, Session, Stage};
fn challenge() -> [u8; 67] {
    let mut p = [0; 67];
    p[..3].copy_from_slice(&[0x10, 4, 1]);
    p
}
#[test]
fn ordered_auth_only_output_and_peer_success_is_not_proof_verification() {
    let (mut s, public) = Session::begin(7, 0, [0; 48]).unwrap();
    assert_eq!(&public.as_bytes()[..4], &[4, 2, 0, 2]);
    assert!(matches!(
        s.receive(7, 1, AUTH_ENDPOINT, &challenge()),
        Ok(Event::ComputeProof(_))
    ));
    let proof = s.proof_prepared(7, 2, [0; 32]).unwrap();
    assert_eq!(proof.as_bytes().len(), 33);
    assert_eq!(proof.as_bytes()[0], 5);
    s.proof_written(7, 3).unwrap();
    assert!(matches!(
        s.receive(7, 4, AUTH_ENDPOINT, &[0x10, 5, 1]),
        Ok(Event::PeerReportedSuccess)
    ));
    assert_eq!(s.stage(), Stage::PeerAccepted); // no authenticated-operation API exists
}
#[test]
fn refuses_unsolicited_success_and_success_before_confirmed_write() {
    let (mut s, _) = Session::begin(1, 0, [0; 48]).unwrap();
    assert!(matches!(
        s.receive(1, 1, AUTH_ENDPOINT, &[0x10, 5, 1]),
        Err(Fault::Order)
    ));
    assert_eq!(s.stage(), Stage::Closed);
    let (mut s, _) = Session::begin(2, 0, [0; 48]).unwrap();
    let p = challenge();
    s.receive(2, 1, AUTH_ENDPOINT, &p).unwrap();
    s.proof_prepared(2, 2, [0; 32]).unwrap();
    assert!(matches!(
        s.receive(2, 3, AUTH_ENDPOINT, &[0x10, 5, 1]),
        Err(Fault::Order)
    ));
}
#[test]
fn stale_generation_preserves_session_but_timeout_clock_and_cancel_close_it() {
    let (mut s, _) = Session::begin(2, 0, [0; 48]).unwrap();
    assert!(matches!(
        s.receive(1, 1, AUTH_ENDPOINT, &challenge()),
        Err(Fault::Stale)
    ));
    assert_eq!(s.stage(), Stage::AwaitChallenge);
    assert!(matches!(s.tick(15000), Err(Fault::Timeout)));
    assert_eq!(s.stage(), Stage::Closed);
    let (mut s, _) = Session::begin(3, 10, [0; 48]).unwrap();
    assert!(matches!(s.tick(9), Err(Fault::Clock)));
    let (mut s, _) = Session::begin(4, 0, [0; 48]).unwrap();
    s.close();
    assert!(matches!(
        s.proof_prepared(4, 1, [0; 32]),
        Err(Fault::Closed)
    ));
}
#[test]
fn malformed_duplicate_and_failed_proof_cannot_unlock_anything() {
    let (mut s, _) = Session::begin(5, 0, [0; 48]).unwrap();
    assert!(matches!(
        s.receive(5, 1, AUTH_ENDPOINT, &[0x10]),
        Err(Fault::Envelope)
    ));
    assert_eq!(s.stage(), Stage::Closed);
    let (mut s, _) = Session::begin(6, 0, [0; 48]).unwrap();
    let p = challenge();
    s.receive(6, 1, AUTH_ENDPOINT, &p).unwrap();
    assert!(matches!(
        s.receive(6, 2, AUTH_ENDPOINT, &p),
        Err(Fault::Order)
    ));
    let (mut s, _) = Session::begin(7, 0, [0; 48]).unwrap();
    s.receive(7, 1, AUTH_ENDPOINT, &p).unwrap();
    s.proof_prepared(7, 2, [0; 32]).unwrap();
    s.proof_written(7, 3).unwrap();
    assert!(matches!(
        s.receive(7, 4, AUTH_ENDPOINT, &[0x10, 5, 0x25]),
        Ok(Event::PeerRejected)
    ));
    assert_eq!(s.stage(), Stage::Closed);
}
