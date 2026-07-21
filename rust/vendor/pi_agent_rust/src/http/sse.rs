//! HTTP SSE compatibility module.
//!
//! `crate::sse` is the canonical SSE implementation. This module exists only
//! to provide the stable `crate::http::sse::*` path.

pub use crate::sse::{SseEvent, SseParser, SseStream};
