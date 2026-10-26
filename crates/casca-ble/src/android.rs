//! Android BLE lab worker. `BleNative.start(Context)` (called once by the MCP
//! service, from a Java thread so btleplug sees the app class loader) starts a
//! Rust thread that takes `ble.*`/`lab.*` jobs from the MCP queue and answers
//! them. Device ids are SHA-256 prefixes of the address, the same opaque ids
//! as `band_devices`; addresses never leave the phone.
use btleplug::api::{
    bleuuid::uuid_from_u16, Central, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    ScanFilter, WriteType,
};
use btleplug::platform::{Adapter, Manager, Peripheral, PeripheralId};
use futures::StreamExt;
use jni::objects::{Global, JClass, JObject, JString};
use jni::{jni_sig, jni_str, Env, EnvUnowned, JavaVM};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;
use uuid::Uuid;

struct Lab {
    context: Global<JObject<'static>>,
    runtime: Runtime,
    /// Opaque id -> Bluetooth address, filled by bonded listings and scans.
    known: Mutex<HashMap<String, String>>,
}

static LAB: OnceLock<Lab> = OnceLock::new();
const MAX_READS: usize = 40;
const SHOWN_BYTES: usize = 64;

fn opaque(address: &str) -> String {
    Sha256::digest(address.as_bytes())[..6]
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Hex plus text when the bytes are printable UTF-8 (revision strings, names).
fn value(bytes: &[u8]) -> Value {
    let shown = &bytes[..bytes.len().min(SHOWN_BYTES)];
    let mut v = json!({"len": bytes.len(), "hex": hex(shown)});
    if let Ok(text) = std::str::from_utf8(bytes) {
        if !text.is_empty() && text.chars().all(|c| !c.is_control()) {
            v["text"] = json!(text);
        }
    }
    v
}

fn short(uuid: &Uuid) -> String {
    let s = uuid.to_string();
    // SIG base UUID 0000xxxx-0000-1000-8000-00805f9b34fb: show xxxx.
    if s.starts_with("0000") && s.ends_with("-0000-1000-8000-00805f9b34fb") {
        s[4..8].to_owned()
    } else {
        s
    }
}

fn parse_uuid(text: &str) -> Result<Uuid, String> {
    match text.len() {
        4 => u16::from_str_radix(text, 16)
            .map(uuid_from_u16)
            .map_err(|_| format!("UUID inválido: {text}")),
        _ => Uuid::parse_str(text).map_err(|_| format!("UUID inválido: {text}")),
    }
}

fn flags(p: CharPropFlags) -> Vec<&'static str> {
    [
        (CharPropFlags::READ, "read"),
        (CharPropFlags::WRITE, "write"),
        (CharPropFlags::WRITE_WITHOUT_RESPONSE, "write_no_response"),
        (CharPropFlags::NOTIFY, "notify"),
        (CharPropFlags::INDICATE, "indicate"),
        (CharPropFlags::BROADCAST, "broadcast"),
        (CharPropFlags::AUTHENTICATED_SIGNED_WRITES, "signed_write"),
        (CharPropFlags::EXTENDED_PROPERTIES, "extended"),
    ]
    .into_iter()
    .filter(|(f, _)| p.contains(*f))
    .map(|(_, n)| n)
    .collect()
}

// ---------- JNI: what btleplug does not offer ----------

fn string(env: &mut Env, o: JObject) -> jni::errors::Result<Option<String>> {
    if o.is_null() {
        return Ok(None);
    }
    Ok(Some(env.cast_local::<JString>(o)?.to_string()))
}

fn bluetooth_adapter<'a>(env: &mut Env<'a>, context: &JObject) -> jni::errors::Result<JObject<'a>> {
    let service = JString::from_str(env, "bluetooth")?;
    let manager = env
        .call_method(
            context,
            jni_str!("getSystemService"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
            &[(&service).into()],
        )?
        .l()?;
    env.call_method(
        &manager,
        jni_str!("getAdapter"),
        jni_sig!("()Landroid/bluetooth/BluetoothAdapter;"),
        &[],
    )?
    .l()
}

/// (address, name, type) of bonded devices. Needs BLUETOOTH_CONNECT.
fn bonded(env: &mut Env, context: &JObject) -> jni::errors::Result<Vec<(String, String, i32)>> {
    let adapter = bluetooth_adapter(env, context)?;
    let set = env
        .call_method(
            &adapter,
            jni_str!("getBondedDevices"),
            jni_sig!("()Ljava/util/Set;"),
            &[],
        )?
        .l()?;
    let iterator = env
        .call_method(
            &set,
            jni_str!("iterator"),
            jni_sig!("()Ljava/util/Iterator;"),
            &[],
        )?
        .l()?;
    let mut found = Vec::new();
    while env
        .call_method(&iterator, jni_str!("hasNext"), jni_sig!("()Z"), &[])?
        .z()?
    {
        let device = env
            .call_method(
                &iterator,
                jni_str!("next"),
                jni_sig!("()Ljava/lang/Object;"),
                &[],
            )?
            .l()?;
        let address = env
            .call_method(
                &device,
                jni_str!("getAddress"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let name = env
            .call_method(
                &device,
                jni_str!("getName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let kind = env
            .call_method(&device, jni_str!("getType"), jni_sig!("()I"), &[])?
            .i()?;
        found.push((
            string(env, address)?.unwrap_or_default(),
            string(env, name)?.unwrap_or_default(),
            kind,
        ));
    }
    Ok(found)
}

fn permission(env: &mut Env, context: &JObject, name: &str) -> jni::errors::Result<bool> {
    let p = JString::from_str(env, name)?;
    let granted = env
        .call_method(
            context,
            jni_str!("checkSelfPermission"),
            jni_sig!("(Ljava/lang/String;)I"),
            &[(&p).into()],
        )?
        .i()?;
    Ok(granted == 0)
}

fn lab_info(env: &mut Env, context: &JObject) -> jni::errors::Result<Value> {
    let build = env.find_class(jni_str!("android/os/Build"))?;
    let manufacturer = env
        .get_static_field(
            &build,
            jni_str!("MANUFACTURER"),
            jni_sig!("Ljava/lang/String;"),
        )?
        .l()?;
    let model = env
        .get_static_field(&build, jni_str!("MODEL"), jni_sig!("Ljava/lang/String;"))?
        .l()?;
    let version = env.find_class(jni_str!("android/os/Build$VERSION"))?;
    let sdk = env
        .get_static_field(&version, jni_str!("SDK_INT"), jni_sig!("I"))?
        .i()?;
    let release = env
        .get_static_field(
            &version,
            jni_str!("RELEASE"),
            jni_sig!("Ljava/lang/String;"),
        )?
        .l()?;
    let package = env
        .call_method(
            context,
            jni_str!("getPackageName"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let pm = env
        .call_method(
            context,
            jni_str!("getPackageManager"),
            jni_sig!("()Landroid/content/pm/PackageManager;"),
            &[],
        )?
        .l()?;
    let info = env
        .call_method(
            &pm,
            jni_str!("getPackageInfo"),
            jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"),
            &[(&package).into(), 0.into()],
        )?
        .l()?;
    let app = env
        .get_field(
            &info,
            jni_str!("versionName"),
            jni_sig!("Ljava/lang/String;"),
        )?
        .l()?;
    let adapter = bluetooth_adapter(env, context)?;
    let enabled = !adapter.is_null()
        && env
            .call_method(&adapter, jni_str!("isEnabled"), jni_sig!("()Z"), &[])?
            .z()?;
    let mut permissions = Map::new();
    for p in ["BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "POST_NOTIFICATIONS"] {
        permissions.insert(
            p.into(),
            json!(permission(
                env,
                context,
                &format!("android.permission.{p}")
            )?),
        );
    }
    Ok(json!({
        "device": format!("{} {}", string(env, manufacturer)?.unwrap_or_default(), string(env, model)?.unwrap_or_default()),
        "android": {"release": string(env, release)?, "sdk": sdk},
        "app_version": string(env, app)?,
        "bluetooth_enabled": enabled,
        "permissions": permissions,
        "ble_stack": "btleplug 0.13.5 (Rust)",
        "write_allowed": casca_mcp::write_allowed(),
    }))
}

fn with_context<T>(
    f: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<T>,
) -> Result<T, String> {
    let lab = LAB.get().ok_or("laboratório BLE não iniciado")?;
    let vm = JavaVM::singleton().map_err(|e| format!("JavaVM: {e}"))?;
    vm.attach_current_thread(|env| f(env, lab.context.as_obj()))
        .map_err(|e| format!("JNI: {e}"))
}

// ---------- btleplug ----------

async fn central() -> Result<Adapter, String> {
    let manager = Manager::new()
        .await
        .map_err(|e| format!("btleplug Manager: {e}"))?;
    manager
        .adapters()
        .await
        .map_err(|e| format!("adapters: {e}"))?
        .into_iter()
        .next()
        .ok_or("sem adaptador Bluetooth".into())
}

/// Opaque id -> address, refreshing bonded devices when the id is unknown.
fn resolve(device: &str) -> Result<String, String> {
    let lab = LAB.get().ok_or("laboratório BLE não iniciado")?;
    if let Some(address) = lab.known.lock().unwrap().get(device) {
        return Ok(address.clone());
    }
    for (address, _, _) in with_context(bonded)? {
        lab.known.lock().unwrap().insert(opaque(&address), address);
    }
    lab.known
        .lock()
        .unwrap()
        .get(device)
        .cloned()
        .ok_or(format!(
            "dispositivo {device} desconhecido: use ble_scan ou band_devices"
        ))
}

async fn connect(address: &str) -> Result<Peripheral, String> {
    let central = central().await?;
    // PeripheralId has no public constructor on Android; serde is the way in.
    let id: PeripheralId =
        serde_json::from_value(json!(address)).map_err(|e| format!("PeripheralId: {e}"))?;
    let peripheral = central
        .add_peripheral(&id)
        .await
        .map_err(|e| format!("add_peripheral: {e}"))?;
    if !peripheral.is_connected().await.unwrap_or(false) {
        tokio::time::timeout(Duration::from_secs(20), peripheral.connect())
            .await
            .map_err(|_| "connect: tempo esgotado (20 s)".to_owned())?
            .map_err(|e| format!("connect: {e}"))?;
    }
    tokio::time::timeout(Duration::from_secs(15), peripheral.discover_services())
        .await
        .map_err(|_| "discover_services: tempo esgotado".to_owned())?
        .map_err(|e| format!("discover_services: {e}"))?;
    Ok(peripheral)
}

fn find(peripheral: &Peripheral, job: &Value) -> Result<Characteristic, String> {
    let wanted = parse_uuid(job["characteristic"].as_str().unwrap_or(""))?;
    let service = job["service"].as_str().map(parse_uuid).transpose()?;
    let matches: Vec<Characteristic> = peripheral
        .characteristics()
        .into_iter()
        .filter(|c| c.uuid == wanted && service.is_none_or(|s| c.service_uuid == s))
        .collect();
    match matches.len() {
        0 => Err(format!("característica {} não encontrada", short(&wanted))),
        1 => Ok(matches.into_iter().next().unwrap()),
        n => Err(format!(
            "{n} características {} em serviços diferentes: informe service",
            short(&wanted)
        )),
    }
}

async fn scan(seconds: u64) -> Result<Value, String> {
    let central = central().await?;
    central
        .start_scan(ScanFilter::default())
        .await
        .map_err(|e| format!("start_scan (precisa de BLUETOOTH_SCAN): {e}"))?;
    tokio::time::sleep(Duration::from_secs(seconds)).await;
    let _ = central.stop_scan().await;
    let bonded: Vec<String> = with_context(bonded)
        .unwrap_or_default()
        .into_iter()
        .map(|(a, _, _)| a)
        .collect();
    let mut devices = Vec::new();
    for p in central
        .peripherals()
        .await
        .map_err(|e| format!("peripherals: {e}"))?
    {
        let Ok(Some(props)) = p.properties().await else {
            continue;
        };
        let address = props.address.to_string();
        let id = opaque(&address);
        LAB.get()
            .unwrap()
            .known
            .lock()
            .unwrap()
            .insert(id.clone(), address.clone());
        let manufacturer: Map<String, Value> = props
            .manufacturer_data
            .iter()
            .map(|(k, v)| (format!("0x{k:04x}"), json!(hex(v))))
            .collect();
        let service_data: Map<String, Value> = props
            .service_data
            .iter()
            .map(|(k, v)| (short(k), json!(hex(v))))
            .collect();
        devices.push(json!({
            "id": id,
            "name": props.local_name.or(props.advertisement_name),
            "rssi": props.rssi,
            "tx_power": props.tx_power_level,
            "bonded": bonded.contains(&address),
            "services": props.services.iter().map(short).collect::<Vec<_>>(),
            "manufacturer_data": manufacturer,
            "service_data": service_data,
        }));
    }
    devices.sort_by_key(|d| -d["rssi"].as_i64().unwrap_or(-200));
    devices.truncate(50);
    Ok(json!({"seconds": seconds, "count": devices.len(), "devices": devices}))
}

async fn explore(address: &str) -> Result<Value, String> {
    let started = Instant::now();
    let peripheral = connect(address).await?;
    let mut reads = 0;
    let mut services = Vec::new();
    for service in peripheral.services() {
        let mut characteristics = Vec::new();
        for c in &service.characteristics {
            let mut entry = json!({
                "uuid": short(&c.uuid),
                "properties": flags(c.properties),
                "descriptors": c.descriptors.iter().map(|d| short(&d.uuid)).collect::<Vec<_>>(),
            });
            if c.properties.contains(CharPropFlags::READ) && reads < MAX_READS {
                reads += 1;
                entry["value"] =
                    match tokio::time::timeout(Duration::from_secs(4), peripheral.read(c)).await {
                        Ok(Ok(bytes)) => value(&bytes),
                        Ok(Err(e)) => json!({"error": e.to_string()}),
                        Err(_) => json!({"error": "tempo esgotado"}),
                    };
            }
            characteristics.push(entry);
        }
        services.push(json!({"uuid": short(&service.uuid), "primary": service.primary, "characteristics": characteristics}));
    }
    let _ = peripheral.disconnect().await;
    Ok(json!({"elapsed_ms": started.elapsed().as_millis() as u64, "services": services}))
}

async fn read(address: &str, job: &Value) -> Result<Value, String> {
    let peripheral = connect(address).await?;
    let outcome = async {
        let c = find(&peripheral, job)?;
        let bytes = tokio::time::timeout(Duration::from_secs(8), peripheral.read(&c))
            .await
            .map_err(|_| "read: tempo esgotado".to_owned())?
            .map_err(|e| format!("read: {e}"))?;
        Ok(json!({"characteristic": short(&c.uuid), "service": short(&c.service_uuid), "value": value(&bytes)}))
    }
    .await;
    let _ = peripheral.disconnect().await;
    outcome
}

async fn notify(address: &str, job: &Value) -> Result<Value, String> {
    let seconds = job["seconds"].as_u64().unwrap_or(10);
    let peripheral = connect(address).await?;
    let outcome = async {
        let c = find(&peripheral, job)?;
        if !c.properties.intersects(CharPropFlags::NOTIFY | CharPropFlags::INDICATE) {
            return Err(format!("{} não tem notify/indicate", short(&c.uuid)));
        }
        let mut stream = peripheral.notifications().await.map_err(|e| format!("notifications: {e}"))?;
        peripheral.subscribe(&c).await.map_err(|e| format!("subscribe: {e}"))?;
        let started = Instant::now();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(seconds);
        let mut received = Vec::new();
        while received.len() < 300 {
            match tokio::time::timeout_at(deadline, stream.next()).await {
                Ok(Some(n)) => received.push(json!({
                    "t_ms": started.elapsed().as_millis() as u64,
                    "characteristic": short(&n.uuid),
                    "value": value(&n.value),
                })),
                _ => break,
            }
        }
        let _ = peripheral.unsubscribe(&c).await;
        Ok(json!({"characteristic": short(&c.uuid), "seconds": seconds, "count": received.len(), "notifications": received}))
    }
    .await;
    let _ = peripheral.disconnect().await;
    outcome
}

async fn write(address: &str, job: &Value) -> Result<Value, String> {
    if !casca_mcp::write_allowed() {
        return Err("escrita bloqueada: a janela aprovada no celular expirou".into());
    }
    let text = job["value_hex"].as_str().unwrap_or("");
    let bytes: Vec<u8> = (0..text.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&text[i..i + 2], 16).map_err(|_| "value_hex inválido".to_owned())
        })
        .collect::<Result<_, _>>()?;
    let peripheral = connect(address).await?;
    let outcome = async {
        let c = find(&peripheral, job)?;
        let kind = if job["response"].as_bool().unwrap_or(true) { WriteType::WithResponse } else { WriteType::WithoutResponse };
        tokio::time::timeout(Duration::from_secs(8), peripheral.write(&c, &bytes, kind))
            .await
            .map_err(|_| "write: tempo esgotado".to_owned())?
            .map_err(|e| format!("write: {e}"))?;
        Ok(json!({"characteristic": short(&c.uuid), "written": bytes.len(), "with_response": matches!(kind, WriteType::WithResponse)}))
    }
    .await;
    let _ = peripheral.disconnect().await;
    outcome
}

fn handle(kind: &str, job: &Value) -> Result<Value, String> {
    let lab = LAB.get().ok_or("laboratório BLE não iniciado")?;
    let device = || resolve(job["device"].as_str().unwrap_or(""));
    match kind {
        "lab.info" => with_context(lab_info),
        "ble.scan" => lab
            .runtime
            .block_on(scan(job["seconds"].as_u64().unwrap_or(6))),
        "ble.explore" => lab.runtime.block_on(explore(&device()?)),
        "ble.read" => lab.runtime.block_on(read(&device()?, job)),
        "ble.notify" => lab.runtime.block_on(notify(&device()?, job)),
        "ble.write" => lab.runtime.block_on(write(&device()?, job)),
        _ => Err(format!("tarefa desconhecida: {kind}")),
    }
}

fn control(request: Value) -> Value {
    serde_json::from_slice(&casca_mcp::control(request.to_string().as_bytes()))
        .unwrap_or(Value::Null)
}

fn worker() {
    loop {
        let jobs = control(json!({"op": "jobs", "prefixes": ["ble.", "lab."]}));
        let list = jobs["jobs"].as_array().cloned().unwrap_or_default();
        for job in &list {
            let kind = job["kind"].as_str().unwrap_or("");
            let started = Instant::now();
            let result = match std::panic::catch_unwind(|| handle(kind, &job["args"])) {
                Ok(Ok(result)) => result,
                Ok(Err(error)) => json!({"error": error}),
                Err(_) => json!({"error": "pânico no worker BLE (registrado no log)"}),
            };
            let outcome = if result.get("error").is_some() {
                "erro"
            } else {
                "ok"
            };
            casca_mcp::log(&format!(
                "{kind}: {outcome} em {} ms",
                started.elapsed().as_millis()
            ));
            control(json!({"op": "complete", "id": job["id"], "result": result}));
        }
        if list.is_empty() {
            std::thread::sleep(Duration::from_millis(150));
        }
    }
}

fn start(env: &mut Env, context: &JObject) -> Result<String, String> {
    if LAB.get().is_some() {
        return Ok("já iniciado".into());
    }
    btleplug::platform::init(env).map_err(|e| format!("btleplug::platform::init: {e}"))?;
    let context = env
        .new_global_ref(context)
        .map_err(|e| format!("global ref: {e}"))?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("casca-ble-rt")
        .enable_all()
        .build()
        .map_err(|e| format!("tokio: {e}"))?;
    if LAB
        .set(Lab {
            context,
            runtime,
            known: Mutex::new(HashMap::new()),
        })
        .is_err()
    {
        return Ok("já iniciado".into());
    }
    std::thread::Builder::new()
        .name("casca-ble".into())
        .spawn(worker)
        .map_err(|e| format!("thread: {e}"))?;
    casca_mcp::log("ble: laboratório iniciado (btleplug 0.13.5, Rust)");
    Ok("ok".into())
}

/// `org.workspace.band7.app.BleNative.start(Context)`; returns "ok" or the error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_workspace_band7_app_BleNative_start<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            let text = start(env, &context).unwrap_or_else(|e| format!("ERRO: {e}"));
            JString::from_str(env, text)
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
