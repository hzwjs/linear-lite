use crate::auth::OAuthManager;
use anyhow::Result;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use reqwest::Client;
use serde_json::{json, Value};

const MCP_URL: &str = "https://linearlite.eobua.cn/mcp";
const MCP_PROTOCOL_VERSION: &str = "2025-06-18";

pub struct McpProxy {
    http: Client,
    oauth: OAuthManager,
    session_id: Option<String>,
    protocol_version: Option<String>,
}

impl McpProxy {
    pub fn new(http: Client, oauth: OAuthManager) -> Self {
        Self {
            http,
            oauth,
            session_id: None,
            protocol_version: None,
        }
    }

    pub async fn forward(&mut self, mut request: Value) -> Result<Vec<Value>> {
        let request_id = request.get("id").cloned();
        let method = request
            .get("method")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        if method == "initialize" {
            self.session_id = None;
            self.protocol_version = None;
            normalize_initialize(&mut request);
        }

        let access_token = match self.oauth.access_token().await {
            Ok(token) => token,
            Err(error) => {
                eprintln!("Linear Lite connector: authorization failed: {error:#}");
                return Ok(request_error(
                    request_id,
                    -32001,
                    "Linear Lite authorization is required.",
                ));
            }
        };

        let mut upstream = self
            .http
            .post(MCP_URL)
            .bearer_auth(access_token)
            .header(ACCEPT, "application/json, text/event-stream")
            .header(CONTENT_TYPE, "application/json")
            .json(&request);
        if let Some(session_id) = &self.session_id {
            upstream = upstream.header("Mcp-Session-Id", session_id);
        }
        if let Some(protocol_version) = &self.protocol_version {
            upstream = upstream.header("Mcp-Protocol-Version", protocol_version);
        }

        let response = match upstream.send().await {
            Ok(response) => response,
            Err(error) => {
                eprintln!("Linear Lite connector: HTTPS request failed: {error}");
                return Ok(request_error(
                    request_id,
                    -32000,
                    "Linear Lite MCP request failed.",
                ));
            }
        };
        let status = response.status();
        if status == reqwest::StatusCode::UNAUTHORIZED {
            if let Err(error) = self.oauth.mark_access_expired() {
                eprintln!("Linear Lite connector: could not mark access token expired: {error:#}");
            }
        }
        if let Some(value) = response
            .headers()
            .get("Mcp-Session-Id")
            .and_then(|item| item.to_str().ok())
        {
            self.session_id = Some(value.to_string());
        }
        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|item| item.to_str().ok())
            .unwrap_or_default()
            .to_string();
        let response_version = response
            .headers()
            .get("Mcp-Protocol-Version")
            .and_then(|item| item.to_str().ok())
            .map(str::to_string);
        let body = match response.bytes().await {
            Ok(body) => body,
            Err(error) => {
                eprintln!("Linear Lite connector: could not read HTTPS response: {error}");
                return Ok(request_error(
                    request_id,
                    -32000,
                    "Linear Lite MCP response could not be read.",
                ));
            }
        };

        if status.as_u16() == 202 && body.is_empty() {
            return Ok(Vec::new());
        }
        let messages = if content_type.contains("text/event-stream") {
            parse_event_stream(&body)
        } else if body.is_empty() {
            Vec::new()
        } else {
            serde_json::from_slice::<Value>(&body)
                .ok()
                .into_iter()
                .collect()
        };

        if !status.is_success() {
            if messages
                .iter()
                .any(|message| message.get("error").is_some())
            {
                return Ok(messages);
            }
            eprintln!("Linear Lite connector: MCP endpoint returned HTTP {status}");
            return Ok(request_error(
                request_id,
                -32000,
                "Linear Lite MCP request failed.",
            ));
        }

        if method == "initialize" {
            self.protocol_version = response_version
                .or_else(|| {
                    messages.iter().find_map(|message| {
                        message
                            .pointer("/result/protocolVersion")
                            .and_then(Value::as_str)
                            .map(str::to_string)
                    })
                })
                .or_else(|| Some(MCP_PROTOCOL_VERSION.to_string()));
        }
        if messages.is_empty() && request_id.is_some() {
            return Ok(request_error(
                request_id,
                -32000,
                "Linear Lite MCP returned an empty response.",
            ));
        }
        Ok(messages)
    }
}

fn normalize_initialize(request: &mut Value) {
    if let Some(params) = request.get_mut("params").and_then(Value::as_object_mut) {
        params.insert(
            "protocolVersion".to_string(),
            Value::String(MCP_PROTOCOL_VERSION.to_string()),
        );
    }
}

fn parse_event_stream(body: &[u8]) -> Vec<Value> {
    let Ok(text) = std::str::from_utf8(body) else {
        return Vec::new();
    };
    let mut messages = Vec::new();
    let mut data_lines = Vec::new();
    for line in text.lines().chain(std::iter::once("")) {
        if line.is_empty() {
            if !data_lines.is_empty() {
                let event = data_lines.join("\n");
                if event != "[DONE]" {
                    if let Ok(message) = serde_json::from_str::<Value>(&event) {
                        messages.push(message);
                    }
                }
                data_lines.clear();
            }
            continue;
        }
        if let Some(data) = line.strip_prefix("data:") {
            data_lines.push(data.strip_prefix(' ').unwrap_or(data).to_string());
        }
    }
    messages
}

fn request_error(id: Option<Value>, code: i64, message: &str) -> Vec<Value> {
    match id {
        Some(id) => vec![json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": {"code": code, "message": message}
        })],
        None => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initialize_uses_server_protocol_without_changing_other_fields() {
        let mut request = json!({
            "jsonrpc": "2.0",
            "id": 7,
            "method": "initialize",
            "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "agent"}}
        });
        normalize_initialize(&mut request);
        assert_eq!(
            request
                .pointer("/params/protocolVersion")
                .and_then(Value::as_str),
            Some("2025-06-18")
        );
        assert_eq!(
            request
                .pointer("/params/clientInfo/name")
                .and_then(Value::as_str),
            Some("agent")
        );
        assert_eq!(request.get("id").and_then(Value::as_i64), Some(7));
    }

    #[test]
    fn event_stream_preserves_array_structured_content() {
        let body = br#"event: message
data: {"jsonrpc":"2.0","id":4,"result":{"structuredContent":[{"id":"LL-1"}],"content":[]}}

"#;
        let messages = parse_event_stream(body);
        assert_eq!(
            messages[0]
                .pointer("/result/structuredContent/0/id")
                .and_then(Value::as_str),
            Some("LL-1")
        );
        assert!(messages[0]
            .pointer("/result/structuredContent")
            .unwrap()
            .is_array());
    }
}
