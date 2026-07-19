use std::fs;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

fn main() {
    let arguments: Vec<_> = std::env::args().collect();
    let session_dir =
        argument_value(&arguments, "--session-dir").map_or_else(std::env::temp_dir, PathBuf::from);
    let session_path = argument_value(&arguments, "--session")
        .map_or_else(|| session_dir.join("fake-session.jsonl"), PathBuf::from);
    let stdin = io::stdin();
    let mut stdout = io::stdout().lock();
    for line in stdin.lock().lines() {
        let Ok(line) = line else {
            break;
        };
        let parsed: serde_json::Value = match serde_json::from_str(&line) {
            Ok(value) => value,
            Err(_) => continue,
        };
        let message_type = parsed.get("type").and_then(|value| value.as_str());
        match message_type {
            Some("fake_exit") => std::process::exit(17),
            Some("fake_agent_start") => {
                ensure_fake_session(&session_path);
                write_json(
                    &mut stdout,
                    &serde_json::json!({
                        "type": "agent_start"
                    }),
                );
            }
            Some("fake_agent_end") => {
                ensure_fake_session(&session_path);
                write_json(&mut stdout, &serde_json::json!({"type": "agent_end"}));
            }
            Some("fake_env") => {
                let runtime_bearer_present = [
                    "OPENHOUSE_PI_TOKEN",
                    "OPENHOUSE_PI_RUNTIME_TOKEN",
                    "OPENHOUSE_RUNTIME_TOKEN",
                    "OPENHOUSE_GATEWAY_TOKEN",
                    "OPENHOUSE_ACCESS_TOKEN",
                    "OPENHOUSE_PI_ACCESS_TOKEN",
                ]
                .iter()
                .any(|name| std::env::var_os(name).is_some());
                write_json(
                    &mut stdout,
                    &serde_json::json!({
                    "type": "fake_env",
                    "bridgeUrl": std::env::var("OPENHOUSE_ANDROID_BRIDGE_URL").ok(),
                    "bridgeTokenPresent": std::env::var_os("OPENHOUSE_ANDROID_BRIDGE_TOKEN").is_some(),
                    "bridgeHelper": std::env::var("OPENHOUSE_ANDROID_BRIDGE_HELPER").ok(),
                    "runtimeBearerPresent": runtime_bearer_present,
                    }),
                );
            }
            Some("fake_args") => write_json(
                &mut stdout,
                &serde_json::json!({
                    "type": "fake_args",
                    "sessionPath": argument_value(&arguments, "--session"),
                }),
            ),
            _ if parsed
                .get("id")
                .and_then(|value| value.as_str())
                .is_some_and(|id| id.starts_with("__openhouse_runtime_state_")) =>
            {
                write_json(
                    &mut stdout,
                    &serde_json::json!({
                        "id": parsed["id"],
                        "type": "response",
                        "command": "get_state",
                        "success": true,
                        "data": {"sessionFile": session_path},
                    }),
                );
            }
            _ => {
                writeln!(stdout, "{line}").expect("write fake Pi output");
                stdout.flush().expect("flush fake Pi output");
            }
        }
    }
}

fn argument_value(arguments: &[String], name: &str) -> Option<String> {
    arguments
        .windows(2)
        .find(|pair| pair[0] == name)
        .map(|pair| pair[1].clone())
}

fn write_json(output: &mut impl Write, value: &serde_json::Value) {
    writeln!(output, "{value}").expect("write fake Pi output");
    output.flush().expect("flush fake Pi output");
}

fn ensure_fake_session(path: &std::path::Path) {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).expect("create fake session parent");
    }
    fs::write(path, b"{\"type\":\"session\"}\n").expect("write fake session");
}
