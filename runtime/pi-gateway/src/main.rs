use std::process::ExitCode;

use clap::Parser;
use openhouse_pi_runtime::api;
use openhouse_pi_runtime::state::AppState;
use openhouse_pi_runtime::{Args, Config};
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .compact()
        .init();

    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            tracing::error!(%error, "runtime stopped");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> openhouse_pi_runtime::Result<()> {
    let config = Config::from_args(Args::parse())?;
    let listener = TcpListener::bind(config.listen).await?;
    let address = listener.local_addr()?;
    let state = AppState::new(config)?;
    state.start_background_tasks();
    tracing::info!(%address, "OpenHouse Pi runtime listening on loopback");

    let shutdown_state = state.clone();
    axum::serve(listener, api::router(state))
        .with_graceful_shutdown(async move {
            shutdown_signal().await;
            shutdown_state.shutdown().await;
        })
        .await?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(error) = tokio::signal::ctrl_c().await {
            tracing::warn!(%error, "failed to install Ctrl-C handler");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(error) => tracing::warn!(%error, "failed to install SIGTERM handler"),
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        () = ctrl_c => {},
        () = terminate => {},
    }
}
