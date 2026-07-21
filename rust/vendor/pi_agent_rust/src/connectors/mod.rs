//! Connectors for extension hostcalls.
//!
//! Connectors provide capability-gated access to host resources (HTTP, filesystem, etc.)
//! for extensions. Each connector validates requests against policy before execution.
//!
//! Hostcall ABI types are re-exported from `crate::extensions` so protocol
//! serialization stays canonical across runtime and connector boundaries.

pub mod http;

use crate::error::Result;
use async_trait::async_trait;
use serde_json::{Value, json};

pub use crate::extensions::{
    HostCallError, HostCallErrorCode, HostCallPayload, HostResultPayload, HostStreamBackpressure,
    HostStreamChunk,
};

/// Trait for connectors that handle hostcalls from extensions.
#[async_trait]
pub trait Connector: Send + Sync {
    /// The capability name this connector handles (e.g., "http", "fs").
    fn capability(&self) -> &'static str;

    /// Dispatch a hostcall to this connector.
    ///
    /// Returns `HostResultPayload` with either success output or error details.
    async fn dispatch(&self, call: &HostCallPayload) -> Result<HostResultPayload>;
}

/// Helper to create a successful host result.
pub fn host_result_ok(call_id: &str, output: Value) -> HostResultPayload {
    HostResultPayload {
        call_id: call_id.to_string(),
        output,
        is_error: false,
        error: None,
        chunk: None,
    }
}

/// Helper to create an error host result.
pub fn host_result_err(
    call_id: &str,
    code: HostCallErrorCode,
    message: impl Into<String>,
    retryable: Option<bool>,
) -> HostResultPayload {
    HostResultPayload {
        call_id: call_id.to_string(),
        output: json!({}),
        is_error: true,
        error: Some(HostCallError {
            code,
            message: message.into(),
            details: None,
            retryable,
        }),
        chunk: None,
    }
}

/// Helper to create an error host result with details.
pub fn host_result_err_with_details(
    call_id: &str,
    code: HostCallErrorCode,
    message: impl Into<String>,
    details: Value,
    retryable: Option<bool>,
) -> HostResultPayload {
    HostResultPayload {
        call_id: call_id.to_string(),
        output: json!({}),
        is_error: true,
        error: Some(HostCallError {
            code,
            message: message.into(),
            details: Some(details),
            retryable,
        }),
        chunk: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn host_result_err_output_is_object() {
        let result = host_result_err("c1", HostCallErrorCode::Io, "fail", None);
        assert!(result.is_error);
        assert!(
            result.output.is_object(),
            "error output must be object, got {:?}",
            result.output
        );
    }

    #[test]
    fn host_result_err_with_details_output_is_object() {
        let result = host_result_err_with_details(
            "c2",
            HostCallErrorCode::Denied,
            "nope",
            json!({"key": "val"}),
            Some(true),
        );
        assert!(result.is_error);
        assert!(
            result.output.is_object(),
            "error output must be object, got {:?}",
            result.output
        );
    }

    #[test]
    fn host_result_ok_output_is_preserved() {
        let payload = json!({"data": 42});
        let result = host_result_ok("c3", payload.clone());
        assert!(!result.is_error);
        assert_eq!(result.output, payload);
    }

    #[test]
    fn all_error_codes_produce_object_output() {
        let codes = [
            HostCallErrorCode::Timeout,
            HostCallErrorCode::Denied,
            HostCallErrorCode::Io,
            HostCallErrorCode::InvalidRequest,
            HostCallErrorCode::Internal,
        ];
        for code in codes {
            let result = host_result_err("c4", code, "msg", None);
            assert!(
                result.output.is_object(),
                "code={code:?} must produce object output"
            );
            let result_d = host_result_err_with_details("c5", code, "msg", json!({}), None);
            assert!(
                result_d.output.is_object(),
                "code={code:?} with details must produce object output"
            );
        }
    }

    #[test]
    fn connectors_hostcall_types_are_canonical_extension_types() {
        fn accepts_extension_call(_: crate::extensions::HostCallPayload) {}
        fn accepts_extension_result(_: crate::extensions::HostResultPayload) {}

        let call = HostCallPayload {
            call_id: "c6".to_string(),
            capability: "http".to_string(),
            method: "http.fetch".to_string(),
            params: json!({"url": "https://example.com"}),
            timeout_ms: Some(1000),
            cancel_token: None,
            context: None,
        };
        accepts_extension_call(call);

        let result = HostResultPayload {
            call_id: "c7".to_string(),
            output: json!({"ok": true}),
            is_error: false,
            error: None,
            chunk: Some(HostStreamChunk {
                index: 1,
                is_last: false,
                backpressure: Some(HostStreamBackpressure {
                    credits: Some(8),
                    delay_ms: Some(5),
                }),
            }),
        };
        accepts_extension_result(result);
    }

    #[test]
    fn host_stream_chunk_serializes_backpressure_fields() {
        let chunk = HostStreamChunk {
            index: 2,
            is_last: false,
            backpressure: Some(HostStreamBackpressure {
                credits: Some(4),
                delay_ms: Some(25),
            }),
        };

        let value = serde_json::to_value(&chunk).expect("serialize host stream chunk");
        assert_eq!(value["index"], json!(2));
        assert_eq!(value["is_last"], json!(false));
        assert_eq!(value["backpressure"]["credits"], json!(4));
        assert_eq!(value["backpressure"]["delay_ms"], json!(25));
    }

    #[test]
    fn protocol_schema_still_declares_host_stream_backpressure_and_object_output() {
        let schema: Value =
            serde_json::from_str(include_str!("../../docs/schema/extension_protocol.json"))
                .expect("parse extension protocol schema");
        let defs = schema
            .get("$defs")
            .and_then(Value::as_object)
            .expect("schema $defs object");

        let host_stream_chunk = defs
            .get("host_stream_chunk")
            .and_then(|v| v.get("properties"))
            .and_then(Value::as_object)
            .expect("host_stream_chunk properties");
        assert!(
            host_stream_chunk.contains_key("backpressure"),
            "schema drift: host_stream_chunk.backpressure missing",
        );

        let output_type = defs
            .get("host_result")
            .and_then(|v| v.get("properties"))
            .and_then(|v| v.get("output"))
            .and_then(|v| v.get("type"))
            .and_then(Value::as_str)
            .expect("host_result.output.type");
        assert_eq!(
            output_type, "object",
            "schema drift: host_result.output must remain object",
        );
    }
}
