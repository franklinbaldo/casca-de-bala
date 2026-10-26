//! Offline host contracts. No BLE, shell or MCP transport.
pub mod auth_bridge;
pub mod basic_snapshot;
pub mod huami_auth;
pub mod huami_handshake;
pub mod microvm;
pub mod zepp_capabilities;
use serde::{Deserialize, Serialize};
pub const OPERATIONS: &[&str] = &[
    "miniapps.list",
    "miniapps.install",
    "miniapps.uninstall",
    "watchfaces.list",
    "watchfaces.install",
    "watchfaces.uninstall",
    "resources.install",
    "firmware.official",
    "firmware.native",
    "sensors.read",
    "interface.show",
    "interface.events",
    "interface.vibrate",
    "interface.image",
    "band.exec",
];
pub const IMPLEMENTED: &[&str] = &["capabilities", "validate-card"];
pub const MAX_INPUT_BYTES: usize = 1024;
pub const MAX_JSON_BYTES: usize = 8192;
pub const MAX_CARD_BYTES: usize = 512;
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", deny_unknown_fields)]
pub enum Command {
    #[serde(rename = "capabilities")]
    Capabilities {},
    #[serde(rename = "validate-card")]
    ValidateCard { text: String },
    #[serde(rename = "miniapps.list")]
    ListMiniapps(ReadTarget),
    #[serde(rename = "miniapps.install")]
    InstallMiniapp(Install),
    #[serde(rename = "miniapps.uninstall")]
    UninstallMiniapp(Remove),
    #[serde(rename = "watchfaces.list")]
    ListWatchfaces(ReadTarget),
    #[serde(rename = "watchfaces.install")]
    InstallWatchface(Install),
    #[serde(rename = "watchfaces.uninstall")]
    UninstallWatchface(Remove),
    #[serde(rename = "resources.install")]
    InstallResources(Resources),
    #[serde(rename = "firmware.official")]
    OfficialFirmware(Firmware),
    #[serde(rename = "firmware.native")]
    NativeFirmware(Firmware),
    #[serde(rename = "sensors.read")]
    ReadSensor(ReadSensor),
    #[serde(rename = "interface.show")]
    ShowCard(ShowCard),
    #[serde(rename = "interface.events")]
    ReadEvents(ReadEvents),
    #[serde(rename = "interface.vibrate")]
    Vibrate(Vibrate),
    #[serde(rename = "interface.image")]
    ShowImage(Install),
    #[serde(rename = "band.exec")]
    ExecuteNative(Firmware),
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OperationClass {
    Local,
    Read,
    Mutation,
    Firmware,
}
impl Command {
    pub fn operation(&self) -> &'static str {
        match self {
            Self::Capabilities { .. } => "capabilities",
            Self::ValidateCard { .. } => "validate-card",
            Self::ListMiniapps(_) => "miniapps.list",
            Self::InstallMiniapp(_) => "miniapps.install",
            Self::UninstallMiniapp(_) => "miniapps.uninstall",
            Self::ListWatchfaces(_) => "watchfaces.list",
            Self::InstallWatchface(_) => "watchfaces.install",
            Self::UninstallWatchface(_) => "watchfaces.uninstall",
            Self::InstallResources(_) => "resources.install",
            Self::OfficialFirmware(_) => "firmware.official",
            Self::NativeFirmware(_) => "firmware.native",
            Self::ReadSensor(_) => "sensors.read",
            Self::ShowCard(_) => "interface.show",
            Self::ReadEvents(_) => "interface.events",
            Self::Vibrate(_) => "interface.vibrate",
            Self::ShowImage(_) => "interface.image",
            Self::ExecuteNative(_) => "band.exec",
        }
    }
    pub fn class(&self) -> OperationClass {
        match self {
            Self::Capabilities { .. } | Self::ValidateCard { .. } => OperationClass::Local,
            Self::ListMiniapps(_) => OperationClass::Read,
            Self::InstallMiniapp(_) => OperationClass::Mutation,
            Self::UninstallMiniapp(_) => OperationClass::Mutation,
            Self::ListWatchfaces(_) => OperationClass::Read,
            Self::InstallWatchface(_) => OperationClass::Mutation,
            Self::UninstallWatchface(_) => OperationClass::Mutation,
            Self::InstallResources(_) => OperationClass::Mutation,
            Self::OfficialFirmware(_) => OperationClass::Firmware,
            Self::NativeFirmware(_) => OperationClass::Firmware,
            Self::ReadSensor(_) => OperationClass::Read,
            Self::ShowCard(_) => OperationClass::Mutation,
            Self::ReadEvents(_) => OperationClass::Read,
            Self::Vibrate(_) => OperationClass::Mutation,
            Self::ShowImage(_) => OperationClass::Mutation,
            Self::ExecuteNative(_) => OperationClass::Firmware,
        }
    }
    fn validate(&self) -> Result<(), Error> {
        match self {
            Self::Capabilities { .. } => Ok(()),
            Self::ValidateCard { text } => validate_card(text),
            Self::ListMiniapps(args) => args.validate(),
            Self::InstallMiniapp(args) => args.validate(),
            Self::UninstallMiniapp(args) => args.validate(),
            Self::ListWatchfaces(args) => args.validate(),
            Self::InstallWatchface(args) => args.validate(),
            Self::UninstallWatchface(args) => args.validate(),
            Self::InstallResources(args) => args.validate(),
            Self::OfficialFirmware(args) => args.validate(),
            Self::NativeFirmware(args) => args.validate(),
            Self::ReadSensor(args) => args.validate(),
            Self::ShowCard(args) => args.validate(),
            Self::ReadEvents(args) => args.validate(),
            Self::Vibrate(args) => args.validate(),
            Self::ShowImage(args) => args.validate(),
            Self::ExecuteNative(args) => args.validate(),
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Model {
    XiaomiSmartBand7,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Sensor {
    HeartRate,
    Steps,
    Spo2,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResourceKind {
    Resources,
    Font,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Target {
    pub device_ref: String,
    pub model: Model,
    pub firmware: String,
    pub hardware: String,
}
impl Target {
    fn validate(&self) -> Result<(), Error> {
        if [&self.device_ref, &self.firmware, &self.hardware]
            .iter()
            .all(|s| valid_ref(s))
        {
            Ok(())
        } else {
            Err(Error::InvalidTarget)
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Package {
    pub artifact_ref: String,
    pub version: String,
    pub sha256: String,
    pub size_bytes: u64,
}
impl Package {
    fn validate(&self) -> Result<(), Error> {
        if valid_ref(&self.artifact_ref)
            && valid_ref(&self.version)
            && self.sha256.len() == 64
            && self
                .sha256
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
            && (1..=64 * 1024 * 1024).contains(&self.size_bytes)
        {
            Ok(())
        } else {
            Err(Error::InvalidPackage)
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReadTarget {
    pub target: Target,
}
impl ReadTarget {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Install {
    pub target: Target,
    pub package: Package,
    pub authorization_ref: String,
}
impl Install {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        self.package.validate()?;
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Remove {
    pub target: Target,
    pub artifact_id: String,
    pub authorization_ref: String,
}
impl Remove {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        if !valid_ref(&self.artifact_id) {
            return Err(Error::InvalidArtifact);
        }
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Resources {
    pub target: Target,
    pub package: Package,
    pub kind: ResourceKind,
    pub authorization_ref: String,
}
impl Resources {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        self.package.validate()?;
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Firmware {
    pub target: Target,
    pub package: Package,
    pub recovery_ref: String,
    pub authorization_ref: String,
}
impl Firmware {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        self.package.validate()?;
        if !valid_ref(&self.recovery_ref) {
            return Err(Error::InvalidRecovery);
        }
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReadSensor {
    pub target: Target,
    pub sensor: Sensor,
}
impl ReadSensor {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ShowCard {
    pub target: Target,
    pub text: String,
    pub authorization_ref: String,
}
impl ShowCard {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        validate_card(&self.text)?;
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReadEvents {
    pub target: Target,
    pub after_sequence: u64,
    pub limit: u16,
}
impl ReadEvents {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        if !(1..=100).contains(&self.limit) {
            return Err(Error::InvalidLimit);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Vibrate {
    pub target: Target,
    pub duration_ms: u16,
    pub authorization_ref: String,
}
impl Vibrate {
    fn validate(&self) -> Result<(), Error> {
        self.target.validate()?;
        if !(1..=1000).contains(&self.duration_ms) {
            return Err(Error::InvalidDuration);
        }
        approval_ref(&self.authorization_ref)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub version: u8,
    pub id: u64,
    pub command: Command,
}
#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct Response {
    pub version: u8,
    pub id: Option<u64>,
    pub mode: &'static str,
    #[serde(flatten)]
    pub outcome: Outcome,
}
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Outcome {
    Capabilities {
        implemented: &'static [&'static str],
    },
    ValidatedCard {
        utf8_bytes: usize,
    },
    Unsupported {
        operation: &'static str,
        reason: &'static str,
    },
    Error {
        code: Error,
    },
}
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Error {
    InputTooLong,
    InvalidEnvelope,
    InvalidCommand,
    UnknownCommand,
    ParametersRequired,
    InvalidCard,
    Version,
    RequestId,
    InvalidTarget,
    InvalidPackage,
    InvalidArtifact,
    InvalidRecovery,
    InvalidAuthorizationRef,
    AuthorizationRequired,
    InvalidLimit,
    InvalidDuration,
    InputIo,
    InvalidUtf8,
    InvalidMode,
}
fn valid_ref(value: &str) -> bool {
    (1..=64).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
}
fn approval_ref(value: &str) -> Result<(), Error> {
    if valid_ref(value) {
        Ok(())
    } else {
        Err(Error::InvalidAuthorizationRef)
    }
}
fn validate_card(text: &str) -> Result<(), Error> {
    if text.trim().is_empty() || text.len() > MAX_CARD_BYTES || text.chars().any(char::is_control) {
        Err(Error::InvalidCard)
    } else {
        Ok(())
    }
}
pub fn parse(input: &str) -> Result<Command, Error> {
    if input.len() > MAX_INPUT_BYTES {
        return Err(Error::InputTooLong);
    }
    if input == "capabilities" {
        return Ok(Command::Capabilities {});
    }
    if let Some(text) = input.strip_prefix("validate-card ") {
        validate_card(text)?;
        return Ok(Command::ValidateCard {
            text: text.to_owned(),
        });
    }
    if OPERATIONS.contains(&input) {
        return Err(Error::ParametersRequired);
    }
    Err(Error::UnknownCommand)
}
pub fn error_response(id: Option<u64>, code: Error) -> Response {
    response(id, Outcome::Error { code })
}
fn response(id: Option<u64>, outcome: Outcome) -> Response {
    Response {
        version: 1,
        id,
        mode: "offline",
        outcome,
    }
}
/// Trusted host policy, never deserialized. Implementations must verify external
/// approval, expiry, target, and full payload. A reference alone grants nothing.
pub trait Authorizer {
    fn permits(&self, request: &Request) -> bool;
}
struct DenyAll;
impl Authorizer for DenyAll {
    fn permits(&self, _request: &Request) -> bool {
        false
    }
}
pub fn dispatch(request: Request) -> Response {
    dispatch_with_authorizer(request, &DenyAll)
}
pub fn dispatch_with_authorizer(request: Request, authorizer: &impl Authorizer) -> Response {
    let id = Some(request.id);
    if request.version != 1 {
        return error_response(id, Error::Version);
    }
    if request.id == 0 {
        return error_response(id, Error::RequestId);
    }
    if let Err(error) = request.command.validate() {
        return error_response(id, error);
    }
    if matches!(
        request.command.class(),
        OperationClass::Mutation | OperationClass::Firmware
    ) && !authorizer.permits(&request)
    {
        return error_response(id, Error::AuthorizationRequired);
    }
    let outcome = match &request.command {
        Command::Capabilities { .. } => Outcome::Capabilities {
            implemented: IMPLEMENTED,
        },
        Command::ValidateCard { text } => Outcome::ValidatedCard {
            utf8_bytes: text.len(),
        },
        command => Outcome::Unsupported {
            operation: command.operation(),
            reason: "adapter_not_implemented",
        },
    };
    response(id, outcome)
}
/// One bounded JSON document. Valid headers retain id on command errors.
/// Malformed headers, encoding or oversized input return id:null.
pub fn handle_json(bytes: &[u8]) -> Response {
    if bytes.len() > MAX_JSON_BYTES {
        return error_response(None, Error::InputTooLong);
    }
    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Envelope {
        version: u8,
        id: u64,
        command: Box<serde_json::value::RawValue>,
    }
    let Ok(envelope) = serde_json::from_slice::<Envelope>(bytes) else {
        return error_response(None, Error::InvalidEnvelope);
    };
    let id = Some(envelope.id);
    if envelope.version != 1 {
        return error_response(id, Error::Version);
    }
    if envelope.id == 0 {
        return error_response(id, Error::RequestId);
    }
    let Ok(command) = serde_json::from_str(envelope.command.get()) else {
        return error_response(id, Error::InvalidCommand);
    };
    dispatch(Request {
        version: envelope.version,
        id: envelope.id,
        command,
    })
}

pub mod huami_chunk;

pub mod health_samples;
pub mod ingestion;

pub mod activity_db;

pub mod event_db;

pub mod ffi;

pub mod sync_session;

pub mod gatt_basics;

pub mod gatt_bridge;
pub mod zepp_bridge;
