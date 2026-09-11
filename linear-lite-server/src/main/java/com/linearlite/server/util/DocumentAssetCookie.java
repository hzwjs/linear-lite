package com.linearlite.server.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * 文档图片资源的路径级会话 Cookie。
 *
 * <p>原生 {@code <img>} 无法携带 Bearer 头，因此图片端点用 HttpOnly Cookie 鉴权；
 * Cookie 只作用于 {@code /api/document-assets}，不会扩散到其它 API。
 */
public final class DocumentAssetCookie {

    public static final String NAME = "document_asset_token";
    public static final String PATH = "/api/document-assets";

    private DocumentAssetCookie() {
    }

    public static void write(HttpServletResponse response, String token, long maxAgeSeconds, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .path(PATH)
                .maxAge(Duration.ofSeconds(Math.max(0, maxAgeSeconds)))
                .sameSite("Lax")
                .secure(secure)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void clear(HttpServletResponse response, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .path(PATH)
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .secure(secure)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static boolean isSecureRequest(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            return forwardedProto.toLowerCase(java.util.Locale.ROOT).contains("https");
        }
        return request.isSecure();
    }
}
