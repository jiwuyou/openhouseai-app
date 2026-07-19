#![allow(
    clippy::missing_errors_doc,
    clippy::must_use_candidate,
    clippy::struct_field_names
)]

pub mod api;
pub mod backup;
pub mod config;
pub mod process;
pub mod state;

use std::io;

pub use config::{Args, Config};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("configuration error: {0}")]
    Config(String),
    #[error("invalid request: {0}")]
    InvalidRequest(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("conflict: {0}")]
    Conflict(String),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("zip error: {0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("process error: {0}")]
    Process(String),
}

pub type Result<T> = std::result::Result<T, Error>;
