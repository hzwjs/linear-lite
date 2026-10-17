use anyhow::{anyhow, bail, Context, Result};
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::Html;
use axum::routing::get;
use axum::Router;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use fs2::FileExt;
use rand::rngs::OsRng;
use rand::RngCore;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{create_dir_all, File, OpenOptions};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, Mutex};
use tokio::time::timeout;
use url::Url;

const ISSUER: &str = "https://linearlite.eobua.cn";
const RESOURCE: &str = "https://linearlite.eobua.cn/mcp";
const CREDENTIAL_SERVICE: &str = "Linear Lite MCP Connector";
const CREDENTIAL_USER: &str = "oauth-session";

#[derive(Clone)]
pub struct OAuthManager {
    http: Client,
}

#[derive(Clone, Serialize, Deserialize)]
struct OAuthSession {
    client_id: String,
    access_token: String,
    refresh_token: String,
    expires_at: u64,
}

#[derive(Deserialize)]
struct ProtectedResourceMetadata {
    resource: String,
    authorization_servers: Vec<String>,
}

#[derive(Deserialize)]
struct AuthorizationServerMetadata {
    issuer: String,
    authorization_endpoint: String,
    token_endpoint: String,
    registration_endpoint: String,
}

#[derive(Deserialize)]
struct RegistrationResponse {
    client_id: String,
}

#[derive(Deserialize)]
struct OAuthTokenResponse {
    access_token: String,
    refresh_token: Option<String>,
    expires_in: Option<u64>,
}

#[derive(Deserialize)]
struct OAuthErrorResponse {
    error: Option<String>,
}

#[derive(Clone)]
struct CallbackState {
    expected_state: String,
    sender: Arc<Mutex<Option<oneshot::Sender<std::result::Result<String, String>>>>>,
}

impl OAuthManager {
    pub fn new(http: Client) -> Self {
        Self { http }
    }

    pub async fn access_token(&self) -> Result<String> {
        let _lock = acquire_credential_lock()?;
        let session = load_session()?;
        let now = epoch_seconds()?;
        let (updated, needs_save) = match session {
            Some(current) if current.expires_at > now.saturating_add(90) => (current, false),
            Some(current) => match self.refresh(&current).await {
                Ok(refreshed) => (refreshed, true),
                Err(RefreshFailure::InvalidGrant) => {
                    (self.authorize(Some(&current.client_id)).await?, true)
                }
                Err(RefreshFailure::Other(error)) => return Err(error),
            },
            None => (self.authorize(None).await?, true),
        };
        if needs_save {
            save_session(&updated)?;
        }
        Ok(updated.access_token)
    }

    pub fn mark_access_expired(&self) -> Result<()> {
        let _lock = acquire_credential_lock()?;
        if let Some(mut session) = load_session()? {
            session.expires_at = 0;
            save_session(&session)?;
        }
        Ok(())
    }

    async fn metadata(&self) -> Result<AuthorizationServerMetadata> {
        let protected_url = format!("{ISSUER}/.well-known/oauth-protected-resource/mcp");
        let protected: ProtectedResourceMetadata = self
            .http
            .get(protected_url)
            .send()
            .await?
            .error_for_status()
            .context("read Linear Lite protected-resource metadata")?
            .json()
            .await
            .context("decode protected-resource metadata")?;
        if protected.resource != RESOURCE
            || !protected
                .authorization_servers
                .iter()
                .any(|item| item == ISSUER)
        {
            bail!("Linear Lite OAuth metadata does not match the configured MCP resource.");
        }

        let server_url = format!("{ISSUER}/.well-known/oauth-authorization-server");
        let metadata: AuthorizationServerMetadata = self
            .http
            .get(server_url)
            .send()
            .await?
            .error_for_status()
            .context("read Linear Lite authorization-server metadata")?
            .json()
            .await
            .context("decode authorization-server metadata")?;
        if metadata.issuer != ISSUER {
            bail!("Linear Lite OAuth issuer does not match the configured issuer.");
        }
        for endpoint in [
            &metadata.authorization_endpoint,
            &metadata.token_endpoint,
            &metadata.registration_endpoint,
        ] {
            let parsed = Url::parse(endpoint).context("parse Linear Lite OAuth endpoint")?;
            if parsed.scheme() != "https" || parsed.host_str() != Some("linearlite.eobua.cn") {
                bail!("Linear Lite OAuth metadata contains an unsupported endpoint.");
            }
        }
        Ok(metadata)
    }

    async fn register_client(&self, redirect_uri: &str) -> Result<String> {
        let metadata = self.metadata().await?;
        let response = self
            .http
            .post(metadata.registration_endpoint)
            .json(&serde_json::json!({
                "client_name": "Linear Lite Local Connector",
                "redirect_uris": [redirect_uri],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
                "scope": "linear-lite:read linear-lite:write"
            }))
            .send()
            .await?;
        if !response.status().is_success() {
            bail!(
                "Linear Lite OAuth client registration failed (HTTP {}).",
                response.status()
            );
        }
        let registered: RegistrationResponse = response
            .json()
            .await
            .context("decode client registration response")?;
        if registered.client_id.trim().is_empty() {
            bail!("Linear Lite OAuth registration returned an empty client identifier.");
        }
        Ok(registered.client_id)
    }

    async fn authorize(&self, client_id: Option<&str>) -> Result<OAuthSession> {
        let metadata = self.metadata().await?;
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .context("start OAuth loopback callback")?;
        let address: SocketAddr = listener.local_addr()?;
        let redirect_uri = format!("http://127.0.0.1:{}/callback", address.port());
        let client_id = match client_id {
            Some(value) => value.to_string(),
            None => self.register_client(&redirect_uri).await?,
        };

        let mut random = [0_u8; 32];
        OsRng.fill_bytes(&mut random);
        let state = URL_SAFE_NO_PAD.encode(random);
        OsRng.fill_bytes(&mut random);
        let code_verifier = URL_SAFE_NO_PAD.encode(random);
        let code_challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(code_verifier.as_bytes()));
        let (sender, receiver) = oneshot::channel();
        let callback_state = CallbackState {
            expected_state: state.clone(),
            sender: Arc::new(Mutex::new(Some(sender))),
        };
        let app = Router::new()
            .route("/callback", get(handle_callback))
            .with_state(callback_state);
        let server = tokio::spawn(async move {
            if let Err(error) = axum::serve(listener, app).await {
                eprintln!("Linear Lite connector: OAuth callback stopped: {error}");
            }
        });

        let mut authorization_url = Url::parse(&metadata.authorization_endpoint)?;
        authorization_url
            .query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", &client_id)
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("code_challenge", &code_challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("state", &state)
            .append_pair("scope", "linear-lite:read linear-lite:write")
            .append_pair("resource", RESOURCE);
        if let Err(error) = webbrowser::open(authorization_url.as_str()) {
            server.abort();
            return Err(anyhow!("could not open the system browser: {error}"));
        }

        let callback = timeout(Duration::from_secs(300), receiver)
            .await
            .context("OAuth callback timed out")?
            .context("OAuth callback ended before authorization")?;
        server.abort();
        let code = callback.map_err(|_| anyhow!("OAuth authorization was denied."))?;

        let response = self
            .http
            .post(metadata.token_endpoint)
            .form(&[
                ("grant_type", "authorization_code"),
                ("client_id", client_id.as_str()),
                ("code", code.as_str()),
                ("redirect_uri", redirect_uri.as_str()),
                ("code_verifier", code_verifier.as_str()),
                ("resource", RESOURCE),
            ])
            .send()
            .await?;
        if !response.status().is_success() {
            bail!(
                "Linear Lite OAuth code exchange failed (HTTP {}).",
                response.status()
            );
        }
        session_from_response(
            client_id,
            response
                .json()
                .await
                .context("decode OAuth token response")?,
        )
    }

    async fn refresh(
        &self,
        current: &OAuthSession,
    ) -> std::result::Result<OAuthSession, RefreshFailure> {
        let metadata = self.metadata().await.map_err(RefreshFailure::Other)?;
        let response = self
            .http
            .post(metadata.token_endpoint)
            .form(&[
                ("grant_type", "refresh_token"),
                ("client_id", current.client_id.as_str()),
                ("refresh_token", current.refresh_token.as_str()),
                ("resource", RESOURCE),
            ])
            .send()
            .await
            .map_err(|error| RefreshFailure::Other(error.into()))?;
        if !response.status().is_success() {
            let status = response.status();
            let invalid_grant = response
                .json::<OAuthErrorResponse>()
                .await
                .ok()
                .and_then(|body| body.error)
                .is_some_and(|error| error == "invalid_grant");
            if invalid_grant {
                return Err(RefreshFailure::InvalidGrant);
            }
            return Err(RefreshFailure::Other(anyhow!(
                "Linear Lite OAuth token refresh failed (HTTP {}).",
                status
            )));
        }
        let body: OAuthTokenResponse = response
            .json()
            .await
            .map_err(|error| RefreshFailure::Other(error.into()))?;
        session_from_response(current.client_id.clone(), body).map_err(RefreshFailure::Other)
    }
}

enum RefreshFailure {
    InvalidGrant,
    Other(anyhow::Error),
}

async fn handle_callback(
    State(state): State<CallbackState>,
    Query(query): Query<HashMap<String, String>>,
) -> (StatusCode, Html<&'static str>) {
    let state_matches = query
        .get("state")
        .is_some_and(|value| value == &state.expected_state);
    let result = if !state_matches {
        Err("OAuth state check failed.".to_string())
    } else if query.contains_key("error") {
        Err("OAuth authorization was denied.".to_string())
    } else if let Some(code) = query.get("code").filter(|value| !value.is_empty()) {
        Ok(code.clone())
    } else {
        Err("OAuth callback did not contain an authorization code.".to_string())
    };
    let success = result.is_ok();
    if let Some(sender) = state.sender.lock().await.take() {
        let _ = sender.send(result);
    }
    if success {
        (
            StatusCode::OK,
            Html("Linear Lite authorization finished. Return to your Agent."),
        )
    } else {
        (
            StatusCode::BAD_REQUEST,
            Html("Linear Lite authorization could not be verified."),
        )
    }
}

fn session_from_response(client_id: String, response: OAuthTokenResponse) -> Result<OAuthSession> {
    if response.access_token.is_empty() {
        bail!("Linear Lite OAuth response has no access token.");
    }
    let refresh_token = response
        .refresh_token
        .filter(|value| !value.is_empty())
        .ok_or_else(|| anyhow!("Linear Lite OAuth response has no refresh token."))?;
    let expires_in = response.expires_in.unwrap_or(300).clamp(60, 2_592_000);
    Ok(OAuthSession {
        client_id,
        access_token: response.access_token,
        refresh_token,
        expires_at: epoch_seconds()?.saturating_add(expires_in),
    })
}

fn credential_entry() -> Result<keyring::Entry> {
    keyring::Entry::new(CREDENTIAL_SERVICE, CREDENTIAL_USER)
        .context("open operating-system credential store")
}

fn load_session() -> Result<Option<OAuthSession>> {
    match credential_entry()?.get_password() {
        Ok(value) => Ok(Some(
            serde_json::from_str(&value).context("decode stored OAuth session")?,
        )),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => {
            Err(anyhow!(error).context("read OAuth session from operating-system credential store"))
        }
    }
}

fn save_session(session: &OAuthSession) -> Result<()> {
    let value = serde_json::to_string(session).context("encode OAuth session")?;
    credential_entry()?
        .set_password(&value)
        .context("write OAuth session to operating-system credential store")
}

fn acquire_credential_lock() -> Result<File> {
    let root: PathBuf = dirs::cache_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("linear-lite-mcp-connector");
    create_dir_all(&root).context("create connector lock directory")?;
    let lock_file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(root.join("oauth-session.lock"))
        .context("open connector credential lock")?;
    lock_file
        .lock_exclusive()
        .context("lock OAuth session for refresh")?;
    Ok(lock_file)
}

fn epoch_seconds() -> Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .context("read system clock")?
        .as_secs())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_challenge_is_base64url_sha256() {
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        assert_eq!(challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
