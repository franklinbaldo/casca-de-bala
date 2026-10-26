// SPDX-License-Identifier: AGPL-3.0-or-later
//! Spike: can btleplug, driven only from Rust, connect to an already bonded
//! Smart Band 7 on Android and read battery and revisions? Everything after the
//! button press is Rust: bonded-device listing via JNI, BLE via btleplug.
//! Bluetooth addresses are never logged; devices show as SHA-256 prefixes,
//! the same opaque id the Casca MCP uses.
use btleplug::api::{bleuuid::uuid_from_u16, Central, Manager as _, Peripheral as _};
use btleplug::platform::{Manager, PeripheralId};
use jni::objects::{JClass, JObject, JString};
use jni::{jni_sig, jni_str, Env, EnvUnowned};
use sha2::{Digest, Sha256};
use std::fmt::Write as _;
use std::time::{Duration, Instant};

const BATTERY: u16 = 0x2A19;
const HARDWARE: u16 = 0x2A27;
const SOFTWARE: u16 = 0x2A28;

struct Bonded {
    address: String,
    name: String,
    kind: i32,
}

fn opaque(address: &str) -> String {
    Sha256::digest(address.as_bytes())[..6].iter().map(|b| format!("{b:02x}")).collect()
}

/// BluetoothAdapter.getBondedDevices(), from Rust through JNI.
fn bonded(env: &mut Env, context: &JObject) -> jni::errors::Result<Vec<Bonded>> {
    let service = JString::from_str(env, "bluetooth")?;
    let manager = env
        .call_method(context, jni_str!("getSystemService"), jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"), &[(&service).into()])?
        .l()?;
    let adapter = env
        .call_method(&manager, jni_str!("getAdapter"), jni_sig!("()Landroid/bluetooth/BluetoothAdapter;"), &[])?
        .l()?;
    let set = env.call_method(&adapter, jni_str!("getBondedDevices"), jni_sig!("()Ljava/util/Set;"), &[])?.l()?;
    let iterator = env.call_method(&set, jni_str!("iterator"), jni_sig!("()Ljava/util/Iterator;"), &[])?.l()?;
    let mut found = Vec::new();
    while env.call_method(&iterator, jni_str!("hasNext"), jni_sig!("()Z"), &[])?.z()? {
        let device = env.call_method(&iterator, jni_str!("next"), jni_sig!("()Ljava/lang/Object;"), &[])?.l()?;
        let address = env.call_method(&device, jni_str!("getAddress"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
        let name = env.call_method(&device, jni_str!("getName"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
        let kind = env.call_method(&device, jni_str!("getType"), jni_sig!("()I"), &[])?.i()?;
        let text = |env: &mut Env, o: JObject| -> jni::errors::Result<String> {
            if o.is_null() {
                return Ok(String::new());
            }
            Ok(env.cast_local::<JString>(o)?.to_string())
        };
        found.push(Bonded { address: text(env, address)?, name: text(env, name)?, kind });
    }
    Ok(found)
}

async fn read_band(log: &mut String, device: &Bonded) -> Result<(), String> {
    let started = Instant::now();
    let manager = Manager::new().await.map_err(|e| format!("Manager::new: {e}"))?;
    let central = manager
        .adapters()
        .await
        .map_err(|e| format!("adapters: {e}"))?
        .into_iter()
        .next()
        .ok_or("nenhum adaptador")?;
    // PeripheralId has no public constructor on Android; serde is the only way in.
    let id: PeripheralId = serde_json::from_value(serde_json::Value::String(device.address.clone()))
        .map_err(|e| format!("PeripheralId: {e}"))?;
    let peripheral = central.add_peripheral(&id).await.map_err(|e| format!("add_peripheral: {e}"))?;
    let step = |log: &mut String, what: &str| {
        let _ = writeln!(log, "  {:>6} ms  {what}", started.elapsed().as_millis());
    };
    tokio::time::timeout(Duration::from_secs(20), peripheral.connect())
        .await
        .map_err(|_| "connect: tempo esgotado (20 s)".to_owned())?
        .map_err(|e| format!("connect: {e}"))?;
    step(log, "conectado");
    let outcome = async {
        tokio::time::timeout(Duration::from_secs(15), peripheral.discover_services())
            .await
            .map_err(|_| "discover_services: tempo esgotado".to_owned())?
            .map_err(|e| format!("discover_services: {e}"))?;
        let characteristics = peripheral.characteristics();
        step(log, &format!("{} características descobertas", characteristics.len()));
        for (short, label) in [(BATTERY, "bateria"), (HARDWARE, "hardware"), (SOFTWARE, "software")] {
            let Some(c) = characteristics.iter().find(|c| c.uuid == uuid_from_u16(short)) else {
                step(log, &format!("{label}: característica ausente"));
                continue;
            };
            match tokio::time::timeout(Duration::from_secs(5), peripheral.read(c)).await {
                Ok(Ok(bytes)) if short == BATTERY => step(log, &format!("bateria: {}%", bytes.first().copied().unwrap_or(0))),
                Ok(Ok(bytes)) => step(log, &format!("{label}: {}", String::from_utf8_lossy(&bytes))),
                Ok(Err(e)) => step(log, &format!("{label}: erro {e}")),
                Err(_) => step(log, &format!("{label}: tempo esgotado")),
            }
        }
        Ok::<(), String>(())
    }
    .await;
    let _ = peripheral.disconnect().await;
    step(log, "desconectado");
    outcome
}

fn run(env: &mut Env, context: &JObject) -> Result<String, String> {
    let mut log = String::from("Spike btleplug 0.13.5 — tudo abaixo roda em Rust\n");
    btleplug::platform::init(env).map_err(|e| format!("btleplug::platform::init: {e}"))?;
    log.push_str("init: ok\n");
    let devices = bonded(env, context).map_err(|e| format!("getBondedDevices via JNI: {e}"))?;
    let _ = writeln!(log, "vinculados (via JNI em Rust): {}", devices.len());
    for d in &devices {
        let kind = match d.kind {
            1 => "classic",
            2 => "le",
            3 => "dual",
            _ => "unknown",
        };
        let _ = writeln!(log, "  {} · {} · {kind}", opaque(&d.address), if d.name.is_empty() { "(sem nome)" } else { &d.name });
    }
    let candidates: Vec<&Bonded> = devices.iter().filter(|d| d.kind != 1).take(4).collect();
    if candidates.is_empty() {
        log.push_str("Nenhum dispositivo LE vinculado para testar.\n");
        return Ok(log);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|e| format!("tokio: {e}"))?;
    for device in candidates {
        let _ = writeln!(log, "\n{} ({})", opaque(&device.address), device.name);
        let mut part = String::new();
        let result = runtime.block_on(read_band(&mut part, device));
        log.push_str(&part);
        if let Err(e) = result {
            let _ = writeln!(log, "  FALHA: {e}");
        }
    }
    Ok(log)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_workspace_band7_spike_Native_run<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            let text = run(env, &context).unwrap_or_else(|e| format!("ERRO: {e}"));
            JString::from_str(env, text)
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
