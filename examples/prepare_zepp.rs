//! Offline request metadata only. No connection, credential or operation dispatch.
use band7_terminal::zepp_capabilities::{prepare, Operation};
fn main() {
    for op in [
        Operation::BatteryRead,
        Operation::FindStart,
        Operation::FindStop,
        Operation::NotificationCapabilities,
    ] {
        println!("{}", serde_json::to_string(&prepare(op)).unwrap());
    }
}
