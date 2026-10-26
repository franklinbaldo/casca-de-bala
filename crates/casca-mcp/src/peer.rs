use std::net::IpAddr;

/// Loopback (Termux Funnel, local tools) and Tailscale addresses only:
/// 100.64.0.0/10 and fd7a:115c:a1e0::/48. Wi-Fi LAN peers are dropped.
pub fn allowed(ip: IpAddr) -> bool {
    match ip.to_canonical() {
        IpAddr::V4(a) => a.is_loopback() || (a.octets()[0] == 100 && a.octets()[1] & 0xc0 == 0x40),
        IpAddr::V6(a) => a.is_loopback() || a.segments()[..3] == [0xfd7a, 0x115c, 0xa1e0],
    }
}

#[cfg(test)]
mod tests {
    use super::allowed;

    #[test]
    fn loopback_and_tailnet_only() {
        for ok in [
            "127.0.0.1",
            "::1",
            "100.64.0.1",
            "100.127.255.254",
            "::ffff:100.101.2.3",
            "fd7a:115c:a1e0::1",
        ] {
            assert!(allowed(ok.parse().unwrap()), "{ok}");
        }
        for no in [
            "192.168.0.10",
            "10.0.0.2",
            "100.63.255.255",
            "100.128.0.1",
            "8.8.8.8",
            "fd7a:115c:a1e1::1",
            "::ffff:192.168.0.1",
        ] {
            assert!(!allowed(no.parse().unwrap()), "{no}");
        }
    }
}
