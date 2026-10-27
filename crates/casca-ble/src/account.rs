//! Huami account: the band's auth key from the owner's Zepp / Zepp Life
//! account, as huami-token does (MIT, Kirill Snezhko; see PROVENANCE). The
//! owner signs in on Zepp's own web login in the phone's browser, with any
//! method it offers (Google, Xiaomi, Apple, e-mail…); this code never sees
//! the password. Zepp sends the browser back to a loopback address the app
//! listens on (RFC 8252), with an access token or an app token in the query.
//! Pure parts live here and are tested on the host; the sockets and HTTPS
//! calls are in android.rs.
use serde_json::Value;
use std::collections::HashMap;

/// Zepp's web login, returning to `redirect` (a loopback URL: Zepp accepts
/// 127.0.0.1 as a return address).
pub fn login_page(redirect: &str) -> String {
    format!(
        "https://user.zepp.com/universalLogin/index.html#/login?project_redirect_uri={}",
        percent_encode(redirect)
    )
}
pub(crate) const LOGIN: &str = "https://api-mifit-us2.zepp.com/v2/client/login";
pub(crate) const DEVICES: &str = "https://api-mifit.zepp.com/users/{user_id}/devices";
pub(crate) const LOGOUT: &str = "https://api-mifit-us2.zepp.com/v1/client/logout";

/// Headers of Zepp's web app on the login call.
pub(crate) const LOGIN_HEADERS: [(&str, &str); 4] = [
    ("app_name", "com.huami.webapp"),
    ("appname", "com.huami.webapp"),
    ("origin", "https://user.zepp.com"),
    ("referer", "https://user.zepp.com/"),
];

/// Form that turns the access token from a sign-in into app tokens.
pub(crate) fn login_form(
    access: &str,
    country: &str,
    device_id: &str,
) -> Vec<(&'static str, String)> {
    let (country_code, state) = country.split_once('-').unwrap_or((country, ""));
    [
        ("code", access),
        ("device_id", device_id),
        ("device_model", "web"),
        ("app_version", "4.3.0"),
        ("dn", "api-mifit.zepp.com,api-user.zepp.com,api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com"),
        ("third_name", "huami"),
        ("app_name", "com.huami.webapp"),
        ("country_code", country_code),
        ("countryState", state),
        ("grant_type", "access_token"),
        ("allow_registration", "false"),
        ("lang", "en"),
    ]
    .into_iter()
    .map(|(k, v)| (k, v.to_owned()))
    .collect()
}

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

/// What the browser brought back to the loopback address.
pub(crate) enum Return {
    /// An access token still to be exchanged at LOGIN.
    Access { access: String, country: String },
    /// A finished session.
    Session(Session),
    /// Neither: the parameter names (never values), to log.
    Other(Vec<String>),
}

/// Decoded query of a request target such as `/path?a=1&b=2`.
pub(crate) fn query(target: &str) -> HashMap<String, String> {
    target
        .split_once('?')
        .map_or("", |(_, q)| q)
        .split('&')
        .filter_map(|pair| {
            let (k, v) = pair.split_once('=')?;
            Some((percent_decode(k), percent_decode(v)))
        })
        .filter(|(_, v)| !v.is_empty())
        .collect()
}

pub(crate) fn classify(params: &HashMap<String, String>) -> Return {
    let get = |k: &str| params.get(k).cloned();
    if let (Some(app_token), Some(user_id)) = (get("apptoken"), get("userid")) {
        return Return::Session(Session {
            app_token,
            user_id,
            login_token: None,
        });
    }
    if let Some(access) = get("access").or_else(|| get("access_token")) {
        let country = get("country_code")
            .or_else(|| get("countrycode"))
            .unwrap_or_else(|| "US".into());
        return Return::Access { access, country };
    }
    let mut names: Vec<String> = params.keys().cloned().collect();
    names.sort();
    Return::Other(names)
}

/// Session from the LOGIN reply.
pub(crate) fn session(reply: &Value) -> Result<Session, String> {
    if let Some(code) = reply.get("error_code") {
        return Err(format!("login recusado pelo Zepp (código {code})"));
    }
    let info = reply
        .get("token_info")
        .ok_or("resposta de login sem token_info")?;
    let field = |k: &str| {
        info.get(k)
            .and_then(Value::as_str)
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
    };
    Ok(Session {
        app_token: field("app_token").ok_or("token_info sem app_token")?,
        user_id: field("user_id").ok_or("token_info sem user_id")?,
        login_token: field("login_token"),
    })
}

fn percent_encode(text: &str) -> String {
    text.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
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
            (b'+', _, _) => {
                out.push(b' ');
                i += 1;
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
    fn login_page_carries_the_loopback_return() {
        let url = login_page("http://127.0.0.1:41234/casca/n0");
        assert!(url.ends_with("project_redirect_uri=http%3A%2F%2F127.0.0.1%3A41234%2Fcasca%2Fn0"));
    }

    #[test]
    fn returns_are_classified() {
        let q = query("/casca/n?apptoken=A%3DB&userid=12&countrycode=BR");
        assert!(
            matches!(classify(&q), Return::Session(s) if s.app_token == "A=B" && s.user_id == "12")
        );
        let q = query("/casca/n?access=X%2BY&country_code=BR-RO");
        assert!(
            matches!(classify(&q), Return::Access { access, country } if access == "X+Y" && country == "BR-RO")
        );
        let q = query("/casca/n?error=0106&appname=x");
        assert!(matches!(classify(&q), Return::Other(n) if n == ["appname", "error"]));
        let form = login_form("T", "BR-RO", "d");
        assert!(
            form.contains(&("country_code", "BR".to_owned()))
                && form.contains(&("countryState", "RO".to_owned()))
        );
    }

    #[test]
    fn login_reply() {
        let ok = json!({"token_info": {"app_token": "a", "login_token": "l", "user_id": "u"}});
        let s = session(&ok).ok().unwrap();
        assert_eq!(
            (s.app_token.as_str(), s.login_token.as_deref()),
            ("a", Some("l"))
        );
        assert!(session(&json!({"error_code": "0106"}))
            .err()
            .unwrap()
            .contains("0106"));
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
