#ifndef BAND7_H
#define BAND7_H
#include <stddef.h>
#include <stdint.h>
/* ABI v1. Caller owns both buffers; no pointer is retained or freed by Rust.
   Return and result.status: 0 ok, 1 invalid input/limit, 2 stale handle,
   3 database/schema error, 4 ingestion/conflict, 5 poisoned lock, 6 caught panic.
   Output must be aligned, non-overlapping and writable; invalid nonnull pointers
   are caller UB. No cancellation/retry implied by an error. */
typedef struct {
    int32_t status;
    uint32_t has_cursor;
    uint64_t handle;
    int64_t cursor;
    uint32_t inserted, records, missing, reserved;
} B7Result;
int32_t b7_call(const uint8_t *input, size_t length, B7Result *result);
/* Stateless basic GATT JSON; input/output max 32768 bytes. No GATT IO.
   0: JSON copied (inspect error field); 1: pointer/input length invalid;
   8: capacity insufficient; 6: caught panic. Errors leave output untouched,
   *written=0 (when written is nonnull). No NUL terminator. No retained memory. */
int32_t b7_gatt_json(const uint8_t *input, size_t length, uint8_t *output,
                    size_t capacity, size_t *written);
/* AUTH reply shape only. No credential input, retained memory or authentication.
   Returns0 invalid/unsupported,1 challenge,2 peer success,3 peer bad-key. */
int32_t b7_auth_reply_kind(uint16_t endpoint, const uint8_t *input, size_t length);
/* Process-local AUTH lifecycle. See src/auth_bridge.rs for op/status/stage.
   No raw key input; output capacity>=512. Caller owns all buffers. */
typedef struct { int32_t status; uint32_t stage; uint64_t handle; size_t written; uint32_t ack; } B7AuthResult;
int32_t b7_auth_session(uint32_t op, uint64_t handle, uint64_t generation, uint64_t now, uint16_t endpoint, const uint8_t *input, size_t length, uint8_t *output, size_t capacity, B7AuthResult *result);
/* Local stateless capability validation/framing ABI; no retained state or IO. */
int32_t b7_zepp(uint32_t op,uint16_t endpoint,uint8_t handle,const uint8_t *input,size_t length,uint8_t *output,size_t capacity,size_t *written);
#endif
