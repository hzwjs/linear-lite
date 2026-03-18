package com.linearlite.server.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 配置与 SPA 静态资源服务：允许 Vue (Vite) 本地开发域名访问；
 * 生产单 JAR 时从 classpath:/static/ 提供前端资源，未匹配路径回退到 index.html。
 * 单 JAR 同机部署时：若请求的 Origin 与当前服务同 host（含端口），则自动放行，无需配置 cors.allowed-origins。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                            List<? extends Resource> locations, ResourceResolverChain chain) {
                        Resource resource = super.resolveResourceInternal(request, requestPath, locations, chain);
                        if (resource == null) {
                            resource = super.resolveResourceInternal(request, "index.html", locations, chain);
                        }
                        return resource;
                    }
                });
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfigurationSource source = (request) -> {
            if (!request.getRequestURI().startsWith("/api/")) {
                return null;
            }
            CorsConfiguration config = new CorsConfiguration();
            List<String> origins = new ArrayList<>(List.of(allowedOrigins.trim().split(",\\s*")));
            origins.removeIf(String::isBlank);
            // 单 JAR 同机部署：请求的 Origin 与当前服务同 host+port 时自动放行
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isEmpty()) {
                String requestHost = request.getServerName();
                int requestPort = request.getServerPort();
                try {
                    java.net.URL u = new java.net.URL(origin);
                    int originPort = u.getPort() <= 0 ? ("https".equals(u.getProtocol()) ? 443 : 80) : u.getPort();
                    if (requestHost.equals(u.getHost()) && requestPort == originPort && !origins.contains(origin)) {
                        origins.add(origin);
                    }
                } catch (Exception ignored) { }
            }
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(true);
            return config;
        };
        return new CorsFilter(source);
    }
}
