package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.entity.CodexRunner;
import com.linearlite.server.service.CodexDispatchService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Runner 端点只接受 Runner Token；注册端点仅接受一次性连接码。 */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CodexRunnerAuthFilter extends OncePerRequestFilter {
    public static final String REQUEST_ATTR_RUNNER_ID = "codexRunnerId";
    private final CodexDispatchService service; private final ObjectMapper objectMapper;
    public CodexRunnerAuthFilter(CodexDispatchService service,ObjectMapper objectMapper){this.service=service;this.objectMapper=objectMapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String p=request.getRequestURI();return !p.startsWith("/api/codex-runner/")||p.equals("/api/codex-runner/register")||"OPTIONS".equalsIgnoreCase(request.getMethod());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {String h=request.getHeader("Authorization");if(h==null||!h.startsWith("Bearer ")){deny(response);return;}try{CodexRunner r=service.authenticate(h.substring(7).trim());request.setAttribute(REQUEST_ATTR_RUNNER_ID,r.getId());chain.doFilter(request,response);}catch(RuntimeException e){deny(response);}}
    private void deny(HttpServletResponse response)throws IOException{response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(401,"Runner Token 无效")));}
}
