// SPDX-License-Identifier: AGPL-3.0-or-later
//! Bluetooth LE from Rust. On Android: btleplug (whose Java half ships in the
//! APK) plus JNI for what btleplug does not cover, such as bonded devices.
//! It runs the MCP lab jobs (`ble.*`, `lab.*`). Nothing on other targets, so
//! host builds and tests never touch BlueZ/D-Bus.
#[cfg(target_os = "android")]
mod android;
