//! Huami account: turn the code from the official Xiaomi OAuth page into the
//! band's auth key, as huami-token did for Zepp Life accounts signed in with
//! Xiaomi (MIT, Kirill Snezhko; see PROVENANCE). The owner signs in on
//! Xiaomi's own page; this code never sees the password. Pure parts live here
//! and are tested on the host; the HTTPS calls are in android.rs.
use serde_json::Value;

/// Opened in the app's WebView; Xiaomi redirects to REDIRECT with ?code=.
pub const AUTHORIZE: &str = "https://account.xiaomi.com/oauth2/authorize?skip_confirm=false&\
client_id=2882303761517383915&pt=0&scope=1+6000+16001+20000&\
redirect_uri=https%3A%2F%2Fhm.xiaomi.com%2Fwatch.do&_locale=en_US&response_type=code";
pub const REDIRECT: &str = "https://hm.xiaomi.com/watch.do";
pub(crate) const LOGIN: &str = "https://account.huami.com/v2/client/login";
pub(crate) const DEVICES: &str = "https://api-mifit-us2.huami.com/users/{user_id}/devices";
pub(crate) const LOGOUT: &str = "https://account-us2.huami.com/v1/client/logout";

/// Form for exchanging the Xiaomi code for Huami tokens.
pub(crate) fn login_form(code: &str, device_id: &str) -> Vec<(&'static str, String)> {
    [
        ("dn", "account.huami.com,api-user.huami.com,app-analytics.huami.com,api-watch.huami.com,api-analytics.huami.com,api-mifit.huami.com"),
        ("app_version", "5.9.2-play_100355"),
        ("source", "com.huami.watch.hmwatchmanager"),
        ("country_code", ""),
        ("third_name", "mi-watch"),
        ("lang", "en"),
        ("device_model", "android_phone"),
        ("allow_registration", "false"),
        ("app_name", "com.huami.midong"),
        ("grant_type", "request_token"),
    ]
    .into_iter()
    .map(|(k, v)| (k, v.to_owned()))
    .chain([("device_id", device_id.to_owned()), ("code", code.to_owned())])
    .collect()
}

pub(crate) struct Session {
    pub app_token: String,
    pub login_token: String,
    pub user_id: String,
}

pub(crate) fn session(reply: &Value) -> Result<Session, String> {
    if let Some(code) = reply.get("error_code") {
        return Err(format!("login recusado pela Huami (error_code {code})"));
    }
    let info = reply
        .get("token_info")
        .ok_or("resposta de login sem token_info")?;
    let field = |k: &str| {
        info.get(k)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or(format!("token_info sem {k}"))
    };
    Ok(Session {
        app_token: field("app_token")?,
        login_token: field("login_token")?,
        user_id: field("user_id")?,
    })
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

/// The `code` query parameter of the OAuth redirect, if this is it.
pub fn code_from_redirect(url: &str) -> Option<String> {
    let query = url.strip_prefix(REDIRECT)?.strip_prefix('?')?;
    query
        .split('&')
        .find_map(|pair| pair.strip_prefix("code="))
        .filter(|c| !c.is_empty())
        .map(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn redirect_code() {
        assert_eq!(
            code_from_redirect("https://hm.xiaomi.com/watch.do?code=C3_AB.cd&state=1").as_deref(),
            Some("C3_AB.cd")
        );
        assert_eq!(
            code_from_redirect("https://evil.example/watch.do?code=x"),
            None
        );
        assert_eq!(
            code_from_redirect("https://hm.xiaomi.com/watch.do?error=denied"),
            None
        );
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
    fn session_and_errors() {
        let ok = json!({"token_info": {"app_token": "a", "login_token": "l", "user_id": "u"}});
        assert_eq!(session(&ok).unwrap().user_id, "u");
        // No Debug on Session (it holds tokens), so no unwrap_err here.
        assert!(session(&json!({"error_code": "0106"}))
            .err()
            .unwrap()
            .contains("0106"));
        assert!(login_form("CODE", &mac("02")).contains(&("code", "CODE".to_owned())));
    }
}
