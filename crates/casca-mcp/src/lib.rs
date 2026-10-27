// SPDX-License-Identifier: AGPL-3.0-or-later
//! MCP server for the phone hub.
//!
//! Streamable HTTP with JSON responses only, reachable from loopback and the
//! tailnet, guarded by bearer tokens that the owner approves on the phone.
//! std + serde_json: no async runtime and no HTTP framework in the APK.
mod http;
mod hub;
#[cfg(feature = "lab")]
mod lab;
mod peer;
mod rpc;

pub use hub::{control, log, write_allowed};
pub use peer::allowed;
