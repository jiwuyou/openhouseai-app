use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use serde_json::Value;

#[test]
fn openhouse_extension_manifest_and_error_contract_are_present() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../extensions/openhouse-tools");
    let manifest: Value = serde_json::from_slice(
        &fs::read(root.join("extension.json")).expect("read extension manifest"),
    )
    .expect("parse extension manifest");
    assert_eq!(manifest["schema"], "pi.ext.manifest.v1");
    assert_eq!(manifest["runtime"], "js");
    assert_eq!(manifest["entrypoint"], "index.ts");
    let capabilities = manifest["capabilities"].as_array().expect("capabilities");
    assert!(
        capabilities.iter().any(|value| value == "exec"),
        "missing exec capability"
    );

    let source = fs::read_to_string(root.join("index.ts")).expect("read extension source");
    for tool in [
        "code_runner",
        "ubuntu_exec",
        "clipboard",
        "intent",
        "share",
        "notification",
    ] {
        assert!(source.contains(&format!("\"{tool}\"")), "missing {tool}");
    }
    assert!(source.contains("isError: true"));
    assert!(source.contains("payload?.isError === true"));
    assert!(source.contains("JSON.stringify({ id: toolCallId, arguments: parameters })"));
    assert!(source.contains("payload?.error?.message"));
    assert!(source.contains("exec sh \"$OPENHOUSE_ANDROID_BRIDGE_HELPER\" \"$@\""));
    assert!(source.contains("await pi.exec(command, args, options)"));
    assert!(!source.contains("OPENHOUSE_ANDROID_BRIDGE_TOKEN"));
    assert!(!source.contains("maxToolIterations"));
    assert!(!source.contains("operit-host"));

    let helper = fs::read_to_string(root.join("android-bridge-request.sh"))
        .expect("read Android Bridge helper");
    assert!(helper.contains("OPENHOUSE_ANDROID_BRIDGE_URL"));
    assert!(!helper.contains("OPENHOUSE_ANDROID_BRIDGE_TOKEN"));
    assert_eq!(
        fs::metadata(root.join("android-bridge-request.sh"))
            .expect("helper metadata")
            .permissions()
            .mode()
            & 0o111,
        0o111,
        "payload helper must retain executable permission"
    );
    let syntax = std::process::Command::new("sh")
        .arg("-n")
        .arg(root.join("android-bridge-request.sh"))
        .status()
        .expect("run sh -n");
    assert!(syntax.success(), "Android Bridge helper must pass sh -n");
}

#[test]
fn index_shell_invocation_reaches_real_helper_with_fixed_request_body() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../extensions/openhouse-tools");
    let helper = root.join("android-bridge-request.sh");
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind fake proxy");
    let address = listener.local_addr().expect("proxy address");
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept helper request");
        let mut reader = BufReader::new(socket.try_clone().expect("clone socket"));
        let mut first_line = String::new();
        reader.read_line(&mut first_line).expect("request line");
        assert_eq!(
            first_line.trim_end(),
            "POST /proxy/v1/tools/clipboard HTTP/1.1"
        );
        let mut content_length = 0_usize;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).expect("header");
            if line == "\r\n" {
                break;
            }
            if let Some(value) = line.to_ascii_lowercase().strip_prefix("content-length:") {
                content_length = value.trim().parse().expect("content length");
            }
        }
        let mut body = vec![0_u8; content_length];
        reader.read_exact(&mut body).expect("request body");
        assert_eq!(
            serde_json::from_slice::<Value>(&body).expect("body json"),
            serde_json::json!({"id":"call-1","arguments":{"operation":"read"}})
        );
        let response = br#"{"callId":"call-1","isError":false,"content":{"text":"ok"}}"#;
        write!(
            socket,
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            response.len()
        )
        .expect("response headers");
        socket.write_all(response).expect("response body");
    });

    let output = std::process::Command::new("sh")
        .args([
            "-c",
            "exec sh \"$OPENHOUSE_ANDROID_BRIDGE_HELPER\" \"$@\"",
            "openhouse-bridge",
            "clipboard",
            r#"{"id":"call-1","arguments":{"operation":"read"}}"#,
        ])
        .env("OPENHOUSE_ANDROID_BRIDGE_HELPER", helper)
        .env(
            "OPENHOUSE_ANDROID_BRIDGE_URL",
            format!("http://{address}/proxy"),
        )
        .output()
        .expect("invoke helper through index shell contract");
    server.join().expect("fake proxy server");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8(output.stdout).expect("helper stdout");
    assert!(stdout.ends_with("\n200"));
    assert!(stdout.contains("\"text\":\"ok\""));
}
