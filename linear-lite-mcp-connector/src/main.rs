mod auth;
mod proxy;

use anyhow::{Context, Result};
use auth::OAuthManager;
use proxy::McpProxy;
use std::io::Write;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("Linear Lite connector: {error:#}");
        std::process::exit(1);
    }
}

async fn run() -> Result<()> {
    let http = reqwest::Client::builder()
        .user_agent(concat!(
            "linear-lite-mcp-connector/",
            env!("CARGO_PKG_VERSION")
        ))
        .timeout(Duration::from_secs(120))
        .build()
        .context("create HTTPS client")?;
    let oauth = OAuthManager::new(http.clone());
    let mut proxy = McpProxy::new(http, oauth);
    let stdin = tokio::io::stdin();
    let mut input = BufReader::new(stdin).lines();
    let stdout = std::io::stdout();
    let mut output = stdout.lock();

    while let Some(line) = input.next_line().await.context("read MCP input")? {
        if line.trim().is_empty() {
            continue;
        }
        let request = match serde_json::from_str(&line) {
            Ok(request) => request,
            Err(error) => {
                eprintln!("Linear Lite connector: invalid JSON-RPC input: {error}");
                continue;
            }
        };
        match proxy.forward(request).await {
            Ok(messages) => {
                for message in messages {
                    serde_json::to_writer(&mut output, &message).context("write MCP response")?;
                    output
                        .write_all(b"\n")
                        .context("write MCP response delimiter")?;
                }
                output.flush().context("flush MCP response")?;
            }
            Err(error) => {
                eprintln!("Linear Lite connector: upstream request failed: {error:#}");
            }
        }
    }
    Ok(())
}
