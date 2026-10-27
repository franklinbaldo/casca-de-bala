//! Huami account: the band's auth key from the owner's Zepp / Zepp Life
//! account, as huami-token does (MIT, Kirill Snezhko; see PROVENANCE). The
//! owner signs in on Zepp's own web login (user.zepp.com), with any method it
//! offers, Xiaomi included; this code never sees the password. Zepp's page
//! leaves `apptoken`, `userid` and `hm-user-login-info` as cookies, which the
//! app hands over. Pure parts live here and are tested on the host; the HTTPS
//! calls are in android.rs.
use serde_json::Value;

/// Opened in the app's WebView.
pub const LOGIN_PAGE: &str = "https://user.zepp.com/universalLogin/index.html#/login";
/// Where the WebView's cookies are read after each page.
pub const COOKIE_URL: &str = "https://user.zepp.com";
pub(crate) const DEVICES: &str = "https://api-mifit.zepp.com/users/{user_id}/devices";
pub(crate) const LOGOUT: &str = "https://api-mifit-us2.zepp.com/v1/client/logout";

/// Query of the device list, as the Zepp app sends it.
pub(crate) fn devices_query(user_id: &str, nonce: &str) -> Vec<(&'static str, String)> {
    [
        ("r", nonce),
        ("r", nonce),
        ("enableMultiDeviceOnMultiType", "true"),
        ("enableMultiDeviceOnMultiType", "true"),
        ("userid", user_id),
        ("appid", nonce),
        ("channel", "a100900101016"),
        ("country", "US"),
        ("cv", "151689_9.12.5"),
        ("device", "android_32"),
        ("device_type", "android_phone"),
        ("enableMultiDevice", "true"),
        ("lang", "en_US"),
        ("timezone", "Europe/London"),
        ("v", "2.0"),
    ]
    .into_iter()
    .map(|(k, v)| (k, v.to_owned()))
    .collect()
}

pub(crate) const DEVICES_HEADERS: [(&str, &str); 9] = [
    ("appplatform", "android_phone"),
    ("appname", "com.huami.midong"),
    ("channel", "a100900101016"),
    ("country", "US"),
    ("cv", "151689_9.12.5"),
    ("lang", "en_US"),
    ("v", "2.0"),
    ("vn", "9.12.5"),
    (
        "user-agent",
        "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)",
    ),
];

pub(crate) struct Session {
    pub app_token: String,
    pub user_id: String,
    pub login_token: Option<String>,
}

/// The signed-in session in a `Cookie` header from Zepp's page, once there.
pub(crate) fn session(cookies: &str) -> Option<Session> {
    let get = |name: &str| {
        cookies.split(';').find_map(|pair| {
            let (k, v) = pair.trim().split_once('=')?;
            (k == name && !v.is_empty()).then(|| percent_decode(v))
        })
    };
    let login_token = get("hm-user-login-info")
        .and_then(|info| serde_json::from_str::<Value>(&info).ok())
        .and_then(|info| {
            info["token_info"]["login_token"]
                .as_str()
                .map(str::to_owned)
        })
        .filter(|t| !t.is_empty());
    Some(Session {
        app_token: get("apptoken").filter(|t| t != "undefined")?,
        user_id: get("userid").filter(|t| t.bytes().all(|b| b.is_ascii_digit()))?,
        login_token,
    })
}

/// True when the cookies already hold a signed-in session.
pub fn signed_in(cookies: &str) -> bool {
    session(cookies).is_some()
}

fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = |b: u8| (b as char).to_digit(16);
        match (
            bytes[i],
            bytes.get(i + 1).copied().and_then(hex),
            bytes.get(i + 2).copied().and_then(hex),
        ) {
            (b'%', Some(h), Some(l)) => {
                out.push((h * 16 + l) as u8);
                i += 3;
            }
            (b, _, _) => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// (MAC, 32-hex auth key) of every device in the account that has a key.
pub(crate) fn keys(devices: &Value) -> Result<Vec<(String, String)>, String> {
    let items = devices
        .get("items")
        .and_then(Value::as_array)
        .ok_or("lista de dispositivos sem items")?;
    Ok(items
        .iter()
        .filter_map(|item| {
            let mac = item.get("macAddress")?.as_str()?.to_ascii_uppercase();
            let info: Value = serde_json::from_str(item.get("additionalInfo")?.as_str()?).ok()?;
            let key = info
                .get("auth_key")?
                .as_str()?
                .trim_start_matches("0x")
                .to_ascii_lowercase();
            (key.len() == 32 && key.bytes().all(|b| b.is_ascii_hexdigit())).then_some((mac, key))
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn session_from_cookies() {
        assert!(!signed_in("_ga=1; lang=en"));
        assert!(!signed_in("apptoken=undefined; userid=123"));
        assert!(!signed_in("apptoken=abc; userid=not-a-number"));
        let info = "%7B%22token_info%22%3A%7B%22login_token%22%3A%22L%2B1%22%7D%7D";
        let s = session(&format!(
            "lang=en; apptoken=A%3DB; userid=1234; hm-user-login-info={info}"
        ))
        .unwrap();
        assert_eq!((s.app_token.as_str(), s.user_id.as_str()), ("A=B", "1234"));
        assert_eq!(s.login_token.as_deref(), Some("L+1"));
        assert!(session("apptoken=x; userid=1")
            .unwrap()
            .login_token
            .is_none());
    }

    /// Synthetic addresses built at run time: no address-shaped literal in the source.
    fn mac(byte: &str) -> String {
        [byte; 6].join(":")
    }

    #[test]
    fn keys_only_well_formed() {
        let devices = json!({"items": [
            {"macAddress": mac("ab"), "additionalInfo": "{\"auth_key\":\"0x00112233445566778899AABBCCDDEEFF\"}"},
            {"macAddress": mac("cd"), "additionalInfo": "{\"auth_key\":\"\"}"},
            {"macAddress": mac("ef"), "additionalInfo": "not json"},
        ]});
        assert_eq!(
            keys(&devices).unwrap(),
            [(mac("AB"), "00112233445566778899aabbccddeeff".to_owned())]
        );
    }

    #[test]
    fn query_repeats_like_the_app() {
        let q = devices_query("42", "n");
        assert_eq!(q.iter().filter(|(k, _)| *k == "r").count(), 2);
        assert!(q.contains(&("userid", "42".to_owned())));
    }
}
