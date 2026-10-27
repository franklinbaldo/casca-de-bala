// SPDX-License-Identifier: AGPL-3.0-or-later
//! `org.workspace.casca.McpNative.control(byte[]) -> byte[]`: the hub's JSON
//! control channel (start, stop, status, decide, jobs, complete, log) for the
//! Gadgetbridge flavor. Device work stays in Gadgetbridge; this library only
//! carries the MCP server and its approvals.
use jni::objects::{JByteArray, JClass};
use jni::EnvUnowned;

/// Requests are small JSON control messages; anything larger is refused.
const MAX_REQUEST: usize = 64 * 1024;

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_workspace_casca_McpNative_control<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    request: JByteArray<'caller>,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JByteArray<'caller>> {
            let bytes = env.convert_byte_array(&request)?;
            let reply = if bytes.is_empty() || bytes.len() > MAX_REQUEST {
                br#"{"error":"bad_request"}"#.to_vec()
            } else {
                std::panic::catch_unwind(|| casca_mcp::control(&bytes))
                    .unwrap_or_else(|_| br#"{"error":"panic"}"#.to_vec())
            };
            env.byte_array_from_slice(&reply)
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
