package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.filter.HostProtocolFilter;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.security.OAuthResourcePolicy;
import com.linearlite.server.service.CollaborationApiService;
import com.linearlite.server.service.CollaborationBatchAuthorizationService;
import com.linearlite.server.service.HostCollaborationBatchService;
import com.linearlite.server.service.HostDeviceAuthorizationService;
import com.linearlite.server.service.ProjectDeviceBindingService;
import com.linearlite.server.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class HostCollaborationBatchControllerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final JwtProperties jwt = new JwtProperties();
    private final McpOAuthProperties oauth = new McpOAuthProperties();
    private final OAuth2AuthorizationService oauthService = mock(OAuth2AuthorizationService.class);
    private final HostDeviceAuthorizationService hostAuthorizations = mock(HostDeviceAuthorizationService.class);
    private final HostCollaborationBatchService hostBatches = mock(HostCollaborationBatchService.class);
    private final CollaborationApiService api = mock(CollaborationApiService.class);
    private final HostDeviceAuthorization grant = new HostDeviceAuthorization();
    private CollaborationBatchAuthorizationService batchAuthorization;
    private MockMvc mvc;
    private String hostToken;
    private String batchToken;

    @BeforeEach void setup() {
        jwt.setSecret("unit-test-host-controller-signing-key-32-bytes");
        oauth.setEnabled(true); oauth.setIssuer("https://example.test"); oauth.setResource("https://example.test/mcp");
        grant.setId(9L); grant.setUserId(1L); grant.setDeviceId("device"); grant.setOauthAuthorizationId("auth-id");
        when(hostAuthorizations.findByAuthorization("auth-id")).thenReturn(grant);
        batchAuthorization = new CollaborationBatchAuthorizationService(mock(ProjectDeviceBindingService.class),
                mock(HostDeviceAuthorizationMapper.class), mock(ProjectMapper.class), mock(ProjectDeviceBindingMapper.class),
                mock(CollaborationBatchMapper.class), mock(TaskMapper.class), jwt);
        Instant now = Instant.now();
        CollaborationBatch batch = new CollaborationBatch();
        batch.setBatchId("batch"); batch.setUserId(1L); batch.setProjectId(6L); batch.setBindingId(30L);
        batch.setBindingRevision(2L); batch.setHostAuthorizationId(9L); batch.setHostInstanceId("host");
        batch.setRunEpoch(1L); batch.setLifecycle("accepted"); batch.setDesiredIntent("RUN");
        batch.setRunLeaseUntil(LocalDateTime.ofInstant(now.plusSeconds(20), ZoneOffset.UTC));
        batch.setDeadlineAt(LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC));
        ProjectDeviceBinding binding = new ProjectDeviceBinding();
        binding.setId(30L); binding.setUserId(1L); binding.setProjectId(6L); binding.setDeviceId("device"); binding.setRevision(2L);
        batchToken = batchAuthorization.issueAccepted(batch, binding, grant).batchToken();
        hostToken = Jwts.builder().subject("1").issuer(oauth.getIssuer())
                .audience().add(OAuthResourcePolicy.hostResource(oauth)).and()
                .claim("scope", List.of(OAuthResourcePolicy.HOST_SCOPE))
                .claim(OAuthResourcePolicy.AUTHORIZATION_ID_CLAIM, "auth-id")
                .issuedAt(Date.from(now.minusSeconds(1))).expiration(Date.from(now.plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
        when(oauthService.findByToken(hostToken, OAuth2TokenType.ACCESS_TOKEN)).thenReturn(oauthAuthorization(hostToken, now));
        @SuppressWarnings("unchecked") ObjectProvider<OAuth2AuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauthService);
        JwtAuthFilter filter = new JwtAuthFilter(new JwtUtil(jwt), json, oauth, provider, hostAuthorizations);
        mvc = MockMvcBuilders.standaloneSetup(new HostCollaborationBatchController(hostBatches, batchAuthorization, api))
                .addFilters(new HostProtocolFilter(json), filter).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void hostContextUsesAuthenticatedIdentityAndRejectsIdentityFieldsInBody() throws Exception {
        var context = new HostCollaborationBatchService.Context("batch", 6L, 30L, 2L, "device", "/work",
                List.of(100L), 2, 60000L, 5, 0L, "queued", "RUN", null, 0L, null, null);
        when(hostBatches.context(1L, 9L, "device", 6L, "batch")).thenReturn(context);
        var result = mvc.perform(post("/api/collaboration-host/v3/batches/context")
                .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":6,\"batchId\":\"batch\"}")).andReturn().getResponse();
        assertEquals(200, result.getStatus());
        assertEquals("no-store", result.getHeader("Cache-Control"));
        assertNotNull(result.getHeader("X-Linear-Lite-Server-Time"));
        assertEquals("/work", json.readTree(result.getContentAsString()).path("data").path("projectDirectory").asText());
        var forged = mvc.perform(post("/api/collaboration-host/v3/batches/context")
                .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":6,\"batchId\":\"batch\",\"userId\":2}")).andReturn().getResponse();
        assertEquals(400, forged.getStatus());
        verify(hostBatches, times(1)).context(anyLong(), anyLong(), anyString(), anyLong(), anyString());
    }

    @Test void businessRouteRequiresHostGrantToMatchSignedBatchGrant() throws Exception {
        when(api.invoke(eq("claim_collaboration_task"), any(), eq(1L))).thenReturn(json.createObjectNode().put("accepted", true));
        String body = "{\"batch\":{\"batchToken\":\"" + batchToken + "\"}}";
        var ok = mvc.perform(post("/api/collaboration-host/v3/tasks/claim").header("Authorization", "Bearer " + hostToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(200, ok.getStatus());
        assertEquals("3", ok.getHeader("X-Linear-Lite-Host-Protocol"));
        grant.setId(10L);
        var wrongGrant = mvc.perform(post("/api/collaboration-host/v3/tasks/claim").header("Authorization", "Bearer " + hostToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(403, wrongGrant.getStatus());
        verify(api, times(1)).invoke(eq("claim_collaboration_task"), any(), eq(1L));
    }

    @Test void oldHostPathsReturnUpgradeBeforeAuthenticationOrDomainCalls() throws Exception {
        for (String route : List.of("/api/collaboration-host/heartbeat", "/api/collaboration-host/batches/pending",
                "/api/collaboration-host/tasks/claim", "/api/collaboration-host/recovery/read",
                "/api/collaboration-host/v2/recovery/finish-accept", "/api/collaboration-host/v3fake/tasks/claim")) {
            var response = mvc.perform(post(route).header("Authorization", "Bearer " + hostToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
            assertEquals(426, response.getStatus(), route);
            assertEquals("3", response.getHeader("X-Linear-Lite-Host-Protocol"), route);
            assertEquals("no-store", response.getHeader("Cache-Control"), route);
            assertTrue(response.getContentAsString().contains("下载新版"), route);
            assertFalse(response.getContentAsString().contains(hostToken), route);
        }
        verifyNoInteractions(hostBatches, api);
    }

    @Test void newRouteRetainsProtocolHeaderWhenAuthenticationFails() throws Exception {
        var response = mvc.perform(post("/api/collaboration-host/v3/tasks/claim")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
        assertEquals(401, response.getStatus());
        assertEquals("3", response.getHeader("X-Linear-Lite-Host-Protocol"));
        verifyNoInteractions(hostBatches, api);
    }

    @Test void contextAndRelationRoutesRequireMatchingHostAndCannotUseAnonymousRecoveryAccess() throws Exception {
        var routes = java.util.Map.of("/documents/list", "list_collaboration_documents",
                "/documents/read", "read_collaboration_document", "/attempts/adopt-context", "adopt_collaboration_context",
                "/attempts/dependencies/change", "change_collaboration_dependency",
                "/attempts/prerequisites/review", "review_collaboration_prerequisite");
        String body = "{\"batch\":{\"batchToken\":\"" + batchToken + "\"}}";
        for (var route : routes.entrySet()) {
            grant.setId(9L);
            when(api.invoke(eq(route.getValue()), any(), eq(1L))).thenReturn(json.createObjectNode().put("accepted", true));
            assertEquals(200, mvc.perform(post("/api/collaboration-host/v3" + route.getKey())
                    .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus());
            grant.setId(10L);
            assertEquals(403, mvc.perform(post("/api/collaboration-host/v3" + route.getKey())
                    .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus());
            assertEquals(401, mvc.perform(post("/api/collaboration-host/v3" + route.getKey())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            verify(api, times(1)).invoke(eq(route.getValue()), any(), eq(1L));
        }
    }

    @Test void recoveryRequiresOriginalBatchSignatureAndDoesNotNeedHostOAuth() throws Exception {
        String expired = expiredBatchToken();
        when(api.invoke(eq("get_collaboration_recovery"), any(), eq(1L))).thenReturn(json.createObjectNode().put("status", "stopped"));
        var result = mvc.perform(post("/api/collaboration-host/v3/recovery/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"batchToken\":\"" + expired + "\"}")).andReturn().getResponse();
        assertEquals(200, result.getStatus());
        var invalid = mvc.perform(post("/api/collaboration-host/v3/recovery/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"batchToken\":\"invalid\"}")).andReturn().getResponse();
        assertEquals(403, invalid.getStatus());
        var unlisted = mvc.perform(post("/api/collaboration-host/v3/recovery/not-a-route")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
        assertEquals(401, unlisted.getStatus());
        verify(api, times(1)).invoke(eq("get_collaboration_recovery"), any(), eq(1L));
    }

    @Test void preparationRequiresOAuthButPreparedRecoveryUsesItsOwnCredential() throws Exception {
        String prepare = """
                {"projectId":6,"batchId":"batch","hostInstanceId":"host"}
                """;
        when(hostBatches.prepareAccept(1L, 9L, "device", 6L, "batch", "host"))
                .thenReturn(new CollaborationBatchAuthorizationService.PreparedAcceptance("batch", 1L, "fixture-proof"));
        assertEquals(401, mvc.perform(post("/api/collaboration-host/v3/batches/prepare-accept")
                .contentType(MediaType.APPLICATION_JSON).content(prepare)).andReturn().getResponse().getStatus());
        var prepared = mvc.perform(post("/api/collaboration-host/v3/batches/prepare-accept")
                .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON)
                .content(prepare)).andReturn().getResponse();
        assertEquals(200, prepared.getStatus());
        assertEquals("no-store", prepared.getHeader("Cache-Control"));
        assertEquals("fixture-proof", json.readTree(prepared.getContentAsString()).path("data").path("recoveryToken").asText());

        // 无准备凭据的旧请求不能创建运行代次。
        assertEquals(400, mvc.perform(post("/api/collaboration-host/v3/batches/accept")
                .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON)
                .content(prepare)).andReturn().getResponse().getStatus());
        verify(hostBatches, never()).accept(any(), any(), any(), any(), any(), any(), any());
        when(hostBatches.accept(1L, 9L, "device", 6L, "batch", "host", "fixture-proof")).thenReturn(
                new CollaborationBatchAuthorizationService.IssuedBatch("batch", "fixture-run",
                        Instant.now().plusSeconds(60), Instant.now().plusSeconds(30), Instant.now().plusSeconds(60), 1L));
        var accepted = mvc.perform(post("/api/collaboration-host/v3/batches/accept")
                .header("Authorization", "Bearer " + hostToken).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":6,"batchId":"batch","hostInstanceId":"host","recoveryToken":"fixture-proof"}
                        """)).andReturn().getResponse();
        assertEquals(200, accepted.getStatus());
        assertEquals("fixture-run", json.readTree(accepted.getContentAsString()).path("data").path("batchToken").asText());

        var stopped = new com.linearlite.server.dto.CollaborationStopConfirmation(true, "no local execution");
        when(hostBatches.finishAccept("fixture-proof", stopped)).thenReturn(
                new HostCollaborationBatchService.Finished("batch", "ended", "STOP", "host_stopped", Instant.now()));
        var result = mvc.perform(post("/api/collaboration-host/v3/recovery/finish-accept")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"recoveryToken":"fixture-proof","confirmation":{"stopped":true,"detail":"no local execution"}}
                        """)).andReturn().getResponse();
        assertEquals(200, result.getStatus());
        assertEquals("ended", json.readTree(result.getContentAsString()).path("data").path("lifecycle").asText());
    }

    private String expiredBatchToken() {
        Instant now = Instant.now();
        return Jwts.builder().subject("collaboration-batch").issuer("linear-lite-collaboration")
                .audience().add("linear-lite-collaboration-batch").and().claim("purpose", "stigmergy-batch-v3")
                .claim("batchId", "batch").claim("userId", 1L).claim("projectId", 6L).claim("bindingId", 30L)
                .claim("bindingRevision", 2L).claim("deviceId", "device").claim("hostAuthorizationId", 9L)
                .claim("hostInstanceId", "host").claim("runEpoch", 1L)
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
    }

    private OAuth2Authorization oauthAuthorization(String token, Instant now) {
        RegisteredClient client = RegisteredClient.withId("client-id").clientId("client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:3000/callback").scope(OAuthResourcePolicy.HOST_SCOPE).build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                now.minusSeconds(1), now.plusSeconds(300), Set.of(OAuthResourcePolicy.HOST_SCOPE));
        return OAuth2Authorization.withRegisteredClient(client).id("auth-id").principalName("1")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE)).accessToken(accessToken).build();
    }
}
