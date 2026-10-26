use band7_terminal::{ffi::b7_auth_reply_kind, huami_auth::AUTH_ENDPOINT};
#[test]
fn auth_classifier_c_abi_is_bounded_stateless_and_not_authentication() {
    let p = [0x10, 5, 1];
    unsafe {
        assert_eq!(b7_auth_reply_kind(AUTH_ENDPOINT, std::ptr::null(), 67), 0);
        assert_eq!(b7_auth_reply_kind(AUTH_ENDPOINT, p.as_ptr(), 0), 0);
        assert_eq!(b7_auth_reply_kind(AUTH_ENDPOINT, [0u8; 68].as_ptr(), 68), 0); // rejected before read
        assert_eq!(b7_auth_reply_kind(0, p.as_ptr(), 3), 0);
        assert_eq!(b7_auth_reply_kind(AUTH_ENDPOINT, p.as_ptr(), 3), 2);
        assert_eq!(
            b7_auth_reply_kind(AUTH_ENDPOINT, [0x10, 5, 0x25].as_ptr(), 3),
            3
        );
    }
}
