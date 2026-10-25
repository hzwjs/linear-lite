package com.linearlite.server.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;

@Controller
@ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
public class McpOAuthUiController {

    private static final List<String> SUPPORTED_SCOPES = List.of("linear-lite:read", "linear-lite:write");
    private final RegisteredClientRepository clients;

    public McpOAuthUiController(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @GetMapping(path = "/oauth/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> login(HttpServletRequest request,
                                        @RequestParam(name = "error", required = false) String error) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String message = error == null ? "" : "<p class=\"error\">账号或密码不正确，请重试。</p>";
        String csrfInput = csrf == null ? "" : hiddenInput(csrf.getParameterName(), csrf.getToken());
        String page = """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>连接 Linear Lite</title>
                <style>body{font:16px system-ui,sans-serif;background:#f6f7f9;color:#202124;margin:0;display:grid;min-height:100vh;place-items:center}main{background:white;padding:32px;border-radius:16px;box-shadow:0 8px 32px #0001;width:min(360px,calc(100%% - 48px))}h1{font-size:22px;margin:0 0 8px}p{color:#60646c;line-height:1.5}label{display:block;margin:18px 0 6px}input{box-sizing:border-box;width:100%%;padding:11px;border:1px solid #c8cbd0;border-radius:8px;font:inherit}button{margin-top:22px;width:100%%;padding:12px;border:0;border-radius:8px;background:#5e6ad2;color:white;font:inherit;cursor:pointer}.error{color:#b42318}</style>
                <main><h1>连接 Linear Lite</h1><p>登录 Linear Lite 后，授权请求中的应用会按你的账号访问已批准的内容。</p>%s
                <form method="post" action="/oauth/login">%s
                <label for="identity">邮箱或用户名</label><input id="identity" name="identity" autocomplete="username" required>
                <label for="password">密码</label><input id="password" type="password" name="password" autocomplete="current-password" required>
                <button type="submit">继续授权</button></form></main></html>
                """.formatted(message, csrfInput);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }

    @GetMapping(path = "/oauth2/consent", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("state") String state,
            @RequestParam("scope") String scope) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("授权应用不存在。");
        }
        String clientName = client.getClientName();
        if (clientName == null || clientName.isBlank()) {
            clientName = "该应用";
        }
        List<String> scopes = Arrays.stream(scope.split(" "))
                .filter(SUPPORTED_SCOPES::contains)
                .distinct()
                .toList();
        if (scopes.isEmpty()) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("Linear Lite 授权范围无效。");
        }
        StringBuilder scopeInputs = new StringBuilder();
        for (String value : scopes) {
            scopeInputs.append(hiddenInput("scope", value));
        }
        String page = """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>授权 Linear Lite</title>
                <style>body{font:16px system-ui,sans-serif;background:#f6f7f9;color:#202124;margin:0;display:grid;min-height:100vh;place-items:center}main{background:white;padding:32px;border-radius:16px;box-shadow:0 8px 32px #0001;width:min(420px,calc(100%% - 48px))}h1{font-size:22px;margin-top:0}li{margin:10px 0;color:#454950}button{margin-top:22px;width:100%%;padding:12px;border:0;border-radius:8px;background:#5e6ad2;color:white;font:inherit;cursor:pointer}</style>
                <main><h1>授权 Linear Lite</h1><p>应用 <strong>%s</strong> 将使用你的 Linear Lite 账号访问以下内容：</p><ul>%s</ul>
                <form method="post" action="/oauth2/authorize">%s%s%s
                <button type="submit" name="user_oauth_approval" value="true">允许访问</button>
                <button type="submit" name="user_oauth_approval" value="false" style="background:#62666d">拒绝</button></form></main></html>
                """.formatted(escape(clientName), scopeList(scopes), hiddenInput("client_id", clientId),
                hiddenInput("state", state), scopeInputs);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }

    private static String scopeList(List<String> scopes) {
        StringBuilder result = new StringBuilder();
        for (String scope : scopes) {
            String label = "linear-lite:read".equals(scope)
                    ? "读取项目、任务与文档"
                    : "创建项目、任务、评论和文档；更新任务与文档";
            result.append("<li>").append(label).append("</li>");
        }
        return result.toString();
    }

    private static String hiddenInput(String name, String value) {
        return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\"" + escape(value) + "\">";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}
