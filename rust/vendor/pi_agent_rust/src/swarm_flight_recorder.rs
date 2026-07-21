//! Deterministic flight-recorder artifacts for multi-agent swarm tests.
//!
//! The recorder consumes already-emitted runtime events, redacts sensitive
//! payload fields, and writes replayable JSONL plus a compact report. It is
//! intentionally independent from live provider credentials so E2E tests can
//! prove swarm behavior with deterministic providers.

use std::collections::{BTreeMap, BTreeSet};

use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::agent::AgentEvent;
use crate::error::{Error, Result};

/// JSONL row schema emitted by [`SwarmFlightRecorder`].
pub const SWARM_FLIGHT_RECORDER_EVENT_SCHEMA: &str = "pi.swarm.flight_recorder.event.v1";

/// Summary report schema emitted by [`SwarmFlightRecorder::build_report`].
pub const SWARM_FLIGHT_RECORDER_REPORT_SCHEMA: &str = "pi.swarm.flight_recorder.report.v1";

/// Replay metadata schema nested inside the summary report.
pub const SWARM_FLIGHT_RECORDER_REPLAY_SCHEMA: &str = "pi.swarm.flight_recorder.replay.v1";

const REDACTED: &str = "[REDACTED]";
const SENSITIVE_KEY_FRAGMENTS: &[&str] = &[
    "api_key",
    "authorization",
    "bearer",
    "content",
    "cookie",
    "key",
    "password",
    "prompt",
    "secret",
    "token",
    "transcript",
];

/// Counts what the recorder redacted from a payload.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightRedactionSummary {
    /// Total number of fields replaced with a redaction marker.
    pub redacted_fields: u64,
    /// Distinct redacted key names, sorted for stable artifacts.
    pub redacted_keys: Vec<String>,
}

#[derive(Debug, Default)]
struct RedactionAccumulator {
    redacted_fields: u64,
    redacted_keys: BTreeSet<String>,
}

impl RedactionAccumulator {
    fn finish(self) -> SwarmFlightRedactionSummary {
        SwarmFlightRedactionSummary {
            redacted_fields: self.redacted_fields,
            redacted_keys: self.redacted_keys.into_iter().collect(),
        }
    }
}

/// One replayable flight-recorder row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightRecorderEvent {
    /// Stable row schema.
    pub schema: String,
    /// Monotonic sequence number assigned by the recorder.
    pub sequence: u64,
    /// Correlates all rows in one swarm scenario.
    pub correlation_id: String,
    /// Logical agent/session actor.
    pub agent_name: String,
    /// Runtime session identifier where available.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    /// Coarse source component, such as `agent`, `session`, `extension`, or `coordination`.
    pub component: String,
    /// Component-specific event kind.
    pub event_kind: String,
    /// Wall-clock timestamp in milliseconds.
    pub timestamp_ms: i64,
    /// Scenario-relative elapsed time in milliseconds.
    pub elapsed_ms: u64,
    /// Event payload after recursive redaction.
    pub payload: Value,
    /// Redaction accounting for this row.
    pub redaction: SwarmFlightRedactionSummary,
}

/// Per-component latency total used by summary reports.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightLatencyHotspot {
    /// Component name.
    pub component: String,
    /// Total retained duration in milliseconds.
    pub total_ms: u64,
    /// Number of contributing samples.
    pub samples: u64,
}

/// A coordination failure or degraded-mode marker surfaced by the report.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightCoordinationFailure {
    /// Event sequence where the failure was observed.
    pub sequence: u64,
    /// Logical agent that observed the failure.
    pub agent_name: String,
    /// Stable event kind.
    pub event_kind: String,
    /// Redacted summary text.
    pub summary: String,
}

/// Replay instructions embedded in the report.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightReplayInstructions {
    /// Stable nested schema.
    pub schema: String,
    /// Command that replays the deterministic scenario.
    pub command: String,
    /// True only if the replay requires live provider credentials.
    pub requires_live_provider_credentials: bool,
    /// Artifact paths that must be present for offline replay/inspection.
    pub artifact_paths: Vec<String>,
}

/// Compact summary for one flight-recorder bundle.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwarmFlightRecorderReport {
    /// Stable report schema.
    pub schema: String,
    /// Correlates this report with its JSONL rows.
    pub correlation_id: String,
    /// Number of JSONL rows in the bundle.
    pub event_count: u64,
    /// Number of distinct logical agents.
    pub agent_count: u64,
    /// Event count by component.
    pub component_counts: BTreeMap<String, u64>,
    /// Highest latency contributors, sorted descending.
    pub dominant_latency_components: Vec<SwarmFlightLatencyHotspot>,
    /// Degraded coordination events found in the bundle.
    pub coordination_failures: Vec<SwarmFlightCoordinationFailure>,
    /// Replay command and credential contract.
    pub replay: SwarmFlightReplayInstructions,
}

/// Append-only recorder for one deterministic swarm scenario.
#[derive(Debug, Clone)]
pub struct SwarmFlightRecorder {
    correlation_id: String,
    next_sequence: u64,
    events: Vec<SwarmFlightRecorderEvent>,
}

impl SwarmFlightRecorder {
    /// Create a recorder for one scenario correlation ID.
    pub fn new(correlation_id: impl Into<String>) -> Result<Self> {
        let correlation_id = correlation_id.into();
        if correlation_id.trim().is_empty() {
            return Err(Error::validation(
                "flight recorder correlation_id cannot be empty".to_string(),
            ));
        }
        Ok(Self {
            correlation_id,
            next_sequence: 0,
            events: Vec::new(),
        })
    }

    /// Borrow all recorded events.
    #[must_use]
    pub fn events(&self) -> &[SwarmFlightRecorderEvent] {
        &self.events
    }

    /// Record a core agent event.
    pub fn record_agent_event(
        &mut self,
        agent_name: impl Into<String>,
        elapsed_ms: u64,
        event: &AgentEvent,
    ) -> Result<()> {
        let payload = serde_json::to_value(event)?;
        let event_kind = payload
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or("agent_event")
            .to_string();
        let session_id = extract_session_id(&payload);
        self.record_value(
            agent_name, session_id, "agent", event_kind, elapsed_ms, payload,
        )
    }

    /// Record a session persistence or recovery snapshot.
    pub fn record_session_snapshot(
        &mut self,
        agent_name: impl Into<String>,
        session_id: impl Into<String>,
        elapsed_ms: u64,
        payload: Value,
    ) -> Result<()> {
        self.record_value(
            agent_name,
            Some(session_id.into()),
            "session",
            "session_snapshot",
            elapsed_ms,
            payload,
        )
    }

    /// Record extension hook/runtime observations.
    pub fn record_extension_event(
        &mut self,
        agent_name: impl Into<String>,
        session_id: impl Into<String>,
        elapsed_ms: u64,
        payload: Value,
    ) -> Result<()> {
        self.record_value(
            agent_name,
            Some(session_id.into()),
            "extension",
            "extension_hooks",
            elapsed_ms,
            payload,
        )
    }

    /// Record external coordination state such as Agent Mail degraded mode.
    pub fn record_coordination_marker(
        &mut self,
        agent_name: impl Into<String>,
        elapsed_ms: u64,
        event_kind: impl Into<String>,
        payload: Value,
    ) -> Result<()> {
        self.record_value(
            agent_name,
            None,
            "coordination",
            event_kind,
            elapsed_ms,
            payload,
        )
    }

    fn record_value(
        &mut self,
        agent_name: impl Into<String>,
        session_id: Option<String>,
        component: impl Into<String>,
        event_kind: impl Into<String>,
        elapsed_ms: u64,
        payload: Value,
    ) -> Result<()> {
        let agent_name = agent_name.into();
        if agent_name.trim().is_empty() {
            return Err(Error::validation(
                "flight recorder agent_name cannot be empty".to_string(),
            ));
        }
        let component = component.into();
        if component.trim().is_empty() {
            return Err(Error::validation(
                "flight recorder component cannot be empty".to_string(),
            ));
        }
        let event_kind = event_kind.into();
        if event_kind.trim().is_empty() {
            return Err(Error::validation(
                "flight recorder event_kind cannot be empty".to_string(),
            ));
        }

        let (payload, redaction) = redact_payload(payload);
        let event = SwarmFlightRecorderEvent {
            schema: SWARM_FLIGHT_RECORDER_EVENT_SCHEMA.to_string(),
            sequence: self.next_sequence,
            correlation_id: self.correlation_id.clone(),
            agent_name,
            session_id,
            component,
            event_kind,
            timestamp_ms: Utc::now().timestamp_millis(),
            elapsed_ms,
            payload,
            redaction,
        };
        self.next_sequence = self.next_sequence.saturating_add(1);
        self.events.push(event);
        Ok(())
    }

    /// Serialize rows as newline-delimited JSON.
    pub fn to_jsonl(&self) -> Result<String> {
        let mut out = String::new();
        for event in &self.events {
            let line = serde_json::to_string(event)?;
            out.push_str(&line);
            out.push('\n');
        }
        Ok(out)
    }

    /// Build a compact report for the current bundle.
    pub fn build_report(
        &self,
        replay_command: impl Into<String>,
        artifact_paths: Vec<String>,
    ) -> SwarmFlightRecorderReport {
        let mut agents = BTreeSet::new();
        let mut component_counts = BTreeMap::new();
        let mut latency_totals = BTreeMap::<String, (u64, u64)>::new();
        let mut coordination_failures = Vec::new();

        for event in &self.events {
            agents.insert(event.agent_name.clone());
            *component_counts
                .entry(event.component.clone())
                .or_insert(0u64) += 1;
            collect_latency_components(&event.payload, &mut latency_totals);
            if matches!(event.component.as_str(), "coordination") && is_coordination_failure(event)
            {
                coordination_failures.push(SwarmFlightCoordinationFailure {
                    sequence: event.sequence,
                    agent_name: event.agent_name.clone(),
                    event_kind: event.event_kind.clone(),
                    summary: coordination_summary(event),
                });
            }
        }

        let mut dominant_latency_components = latency_totals
            .into_iter()
            .map(
                |(component, (total_ms, samples))| SwarmFlightLatencyHotspot {
                    component,
                    total_ms,
                    samples,
                },
            )
            .collect::<Vec<_>>();
        dominant_latency_components.sort_by(|left, right| {
            right
                .total_ms
                .cmp(&left.total_ms)
                .then_with(|| left.component.cmp(&right.component))
        });

        SwarmFlightRecorderReport {
            schema: SWARM_FLIGHT_RECORDER_REPORT_SCHEMA.to_string(),
            correlation_id: self.correlation_id.clone(),
            event_count: u64::try_from(self.events.len()).unwrap_or(u64::MAX),
            agent_count: u64::try_from(agents.len()).unwrap_or(u64::MAX),
            component_counts,
            dominant_latency_components,
            coordination_failures,
            replay: SwarmFlightReplayInstructions {
                schema: SWARM_FLIGHT_RECORDER_REPLAY_SCHEMA.to_string(),
                command: replay_command.into(),
                requires_live_provider_credentials: false,
                artifact_paths,
            },
        }
    }
}

/// Parse and validate a flight-recorder JSONL bundle.
pub fn validate_swarm_flight_recorder_jsonl(jsonl: &str) -> Result<Vec<SwarmFlightRecorderEvent>> {
    let mut events = Vec::new();
    for (line_index, line) in jsonl.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let event: SwarmFlightRecorderEvent = serde_json::from_str(line)?;
        if event.schema != SWARM_FLIGHT_RECORDER_EVENT_SCHEMA {
            return Err(Error::validation(format!(
                "flight recorder line {} has unsupported schema {}",
                line_index + 1,
                event.schema
            )));
        }
        let expected_sequence = u64::try_from(events.len()).unwrap_or(u64::MAX);
        if event.sequence != expected_sequence {
            return Err(Error::validation(format!(
                "flight recorder line {} has non-monotonic sequence {}",
                line_index + 1,
                event.sequence
            )));
        }
        if event.correlation_id.trim().is_empty()
            || event.agent_name.trim().is_empty()
            || event.component.trim().is_empty()
            || event.event_kind.trim().is_empty()
        {
            return Err(Error::validation(format!(
                "flight recorder line {} has an empty required field",
                line_index + 1
            )));
        }
        events.push(event);
    }
    if events.is_empty() {
        return Err(Error::validation(
            "flight recorder JSONL contains no events".to_string(),
        ));
    }
    Ok(events)
}

fn redact_payload(payload: Value) -> (Value, SwarmFlightRedactionSummary) {
    let mut accumulator = RedactionAccumulator::default();
    let redacted = redact_value(payload, None, &mut accumulator);
    (redacted, accumulator.finish())
}

fn redact_value(value: Value, key: Option<&str>, accumulator: &mut RedactionAccumulator) -> Value {
    if key.is_some_and(is_sensitive_key) {
        accumulator.redacted_fields = accumulator.redacted_fields.saturating_add(1);
        if let Some(key) = key {
            accumulator.redacted_keys.insert(key.to_string());
        }
        return Value::String(REDACTED.to_string());
    }

    match value {
        Value::Array(values) => Value::Array(
            values
                .into_iter()
                .map(|value| redact_value(value, key, accumulator))
                .collect(),
        ),
        Value::Object(map) => {
            let redacted = map
                .into_iter()
                .map(|(key, value)| {
                    let value = redact_value(value, Some(&key), accumulator);
                    (key, value)
                })
                .collect::<Map<_, _>>();
            Value::Object(redacted)
        }
        other => other,
    }
}

fn is_sensitive_key(key: &str) -> bool {
    let normalized = key.to_ascii_lowercase();
    SENSITIVE_KEY_FRAGMENTS
        .iter()
        .any(|fragment| normalized.contains(fragment))
}

fn extract_session_id(payload: &Value) -> Option<String> {
    payload
        .get("sessionId")
        .or_else(|| payload.get("session_id"))
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
}

fn collect_latency_components(payload: &Value, totals: &mut BTreeMap<String, (u64, u64)>) {
    let Some(latency) = payload.get("latencyBreakdown") else {
        return;
    };
    for key in [
        "providerStreaming",
        "localTools",
        "extensionHostcalls",
        "persistence",
    ] {
        let Some(duration_ms) = latency
            .get(key)
            .and_then(|value| value.get("durationMs"))
            .and_then(Value::as_u64)
        else {
            continue;
        };
        let entry = totals.entry(key.to_string()).or_insert((0, 0));
        entry.0 = entry.0.saturating_add(duration_ms);
        entry.1 = entry.1.saturating_add(1);
    }
}

fn is_coordination_failure(event: &SwarmFlightRecorderEvent) -> bool {
    let kind = event.event_kind.to_ascii_lowercase();
    if kind.contains("failure") || kind.contains("degraded") || kind.contains("fallback") {
        return true;
    }
    event
        .payload
        .get("status")
        .and_then(Value::as_str)
        .is_some_and(|status| {
            let status = status.to_ascii_lowercase();
            matches!(status.as_str(), "red" | "error" | "degraded")
        })
}

fn coordination_summary(event: &SwarmFlightRecorderEvent) -> String {
    event
        .payload
        .get("summary")
        .and_then(Value::as_str)
        .or_else(|| event.payload.get("mode").and_then(Value::as_str))
        .unwrap_or(event.event_kind.as_str())
        .to_string()
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn redacts_sensitive_payload_keys_recursively() {
        let (redacted, summary) = redact_payload(json!({
            "token": "abc",
            "nested": { "api_key": "def", "safe": "ok" },
        }));
        assert_eq!(redacted["token"], REDACTED);
        assert_eq!(redacted["nested"]["api_key"], REDACTED);
        assert_eq!(redacted["nested"]["safe"], "ok");
        assert_eq!(summary.redacted_fields, 2);
        assert_eq!(summary.redacted_keys, vec!["api_key", "token"]);
    }

    #[test]
    fn validates_monotonic_jsonl_rows() {
        let mut recorder = SwarmFlightRecorder::new("corr-test").expect("recorder");
        recorder
            .record_coordination_marker(
                "agent-a",
                0,
                "agent_mail_degraded",
                json!({"status": "red", "summary": "schema missing"}),
            )
            .expect("record marker");
        let jsonl = recorder.to_jsonl().expect("jsonl");
        let rows = validate_swarm_flight_recorder_jsonl(&jsonl).expect("valid jsonl");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].sequence, 0);
    }
}
