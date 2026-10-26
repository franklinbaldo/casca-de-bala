// SPDX-License-Identifier: AGPL-3.0-or-later
//! Local ABI only, not BLE messages or authorization. No state or retained memory.
use crate::zepp_capabilities as z;
fn process(op: u32, endpoint: u16, handle: u8, input: &[u8]) -> Result<Vec<u8>, ()> {
    match op {
        0 => z::decode_battery(input)
            .map(|_| input.to_vec())
            .map_err(|_| ()),
        1 => z::decode_find_event(input)
            .map(|_| input.to_vec())
            .map_err(|_| ()),
        2 => z::decode_notification_capabilities(input)
            .map(|_| input.to_vec())
            .map_err(|_| ()),
        4 if matches!(endpoint, 0 | 0x29) && input == [3] && handle > 2 => {
            let frames =
                crate::huami_chunk::encode_plain(endpoint, handle, 23, input).map_err(|_| ())?;
            let mut out = Vec::new();
            for frame in frames {
                out.extend_from_slice(&(frame.len() as u16).to_le_bytes());
                out.extend(frame);
            }
            Ok(out)
        }
        _ => Err(()),
    }
}
/// Validate payload (0 battery,1 find,2 notification) or encode read-only plaintext
/// request (4) using shared caller-owned handle. Returns0 success/1 invalid/8 capacity.
/// # Safety
/// Nonnull readable input and writable nonoverlapping output/written are required;
/// no pointers retained. Invalid inputs leave output untouched and written=0.
#[no_mangle]
pub unsafe extern "C" fn b7_zepp(
    op: u32,
    endpoint: u16,
    handle: u8,
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> i32 {
    if written.is_null() {
        return 1;
    }
    unsafe {
        *written = 0;
    }
    if input.is_null() || output.is_null() || length == 0 || length > 512 {
        return 1;
    }
    let result = std::panic::catch_unwind(|| {
        process(op, endpoint, handle, unsafe {
            std::slice::from_raw_parts(input, length)
        })
    });
    let Ok(Ok(bytes)) = result else {
        return 1;
    };
    if bytes.len() > capacity {
        return 8;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len());
        *written = bytes.len();
    }
    0
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn abi_bounds() {
        let mut b = [0u8; 21];
        b[0] = 4;
        b[2] = 74;
        let mut out = [0xa5; 32];
        let mut n = 99;
        unsafe {
            assert_eq!(
                b7_zepp(0, 0, 0, b.as_ptr(), 21, out.as_mut_ptr(), 20, &mut n),
                8
            );
            assert_eq!(n, 0);
            assert_eq!(out, [0xa5; 32]);
            assert_eq!(
                b7_zepp(0, 0, 0, b.as_ptr(), 21, out.as_mut_ptr(), 32, &mut n),
                0
            );
            assert_eq!(n, 21);
            assert_eq!(out[2], 74);
        }
        assert!(process(4, 0x1a, 3, &[3]).is_err());
        assert!(process(4, 0, 2, &[3]).is_err());
        assert!(process(4, 0, 3, &[3]).is_ok());
    }
}
