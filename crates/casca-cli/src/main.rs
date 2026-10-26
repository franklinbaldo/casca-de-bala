use casca_core::{
    dispatch, error_response, handle_json, parse, Error, Outcome, Request, Response,
    MAX_INPUT_BYTES, MAX_JSON_BYTES,
};
use std::io::{self, Read};

fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    let gatt = args.as_slice() == ["--gatt-json"];
    let json = match args.as_slice() {
        [] => false,
        [mode] if mode == "--json" || mode == "--gatt-json" => true,
        _ => finish(error_response(None, Error::InvalidMode)),
    };
    let max = if gatt {
        casca_core::gatt_bridge::MAX_INPUT
    } else if json {
        MAX_JSON_BYTES
    } else {
        MAX_INPUT_BYTES
    };
    let mut bytes = Vec::new();
    if io::stdin()
        .take((max + 1) as u64)
        .read_to_end(&mut bytes)
        .is_err()
    {
        finish(error_response(None, Error::InputIo));
    }
    if gatt {
        let output = casca_core::gatt_bridge::process(&bytes);
        let failed = serde_json::from_slice::<serde_json::Value>(&output)
            .expect("GATT JSON")
            .get("error")
            .is_some();
        println!("{}", String::from_utf8(output).expect("UTF-8 JSON"));
        std::process::exit(i32::from(failed));
    }
    if bytes.len() > max {
        finish(error_response(None, Error::InputTooLong));
    }
    if json {
        finish(handle_json(&bytes));
    }
    let Ok(input) = std::str::from_utf8(&bytes) else {
        finish(error_response(None, Error::InvalidUtf8));
    };
    // Only a final LF or CRLF is framing; a lone CR remains invalid input.
    let input = input
        .strip_suffix("\r\n")
        .or_else(|| input.strip_suffix('\n'))
        .unwrap_or(input);
    let command = match parse(input) {
        Ok(command) => command,
        Err(code) => finish(error_response(Some(1), code)),
    };
    finish(dispatch(Request {
        version: 1,
        id: 1,
        command,
    }));
}

fn finish(response: Response) -> ! {
    let code = match response.outcome {
        Outcome::Error { .. } => 1,
        Outcome::Unsupported { .. } => 2,
        _ => 0,
    };
    println!(
        "{}",
        serde_json::to_string(&response).expect("response is serializable")
    );
    std::process::exit(code);
}
