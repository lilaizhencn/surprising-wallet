package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

final class CustodySessionCookie {
    static final String NAME = "SW_CUSTODY_SESSION";

    private CustodySessionCookie() {
    }

    static String read(Cookie[] cookies) {
        if (cookies == null) {
            throw new CustodyUnauthorizedException("session cookie required");
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && cookie.getValue().startsWith("cs_") && cookie.getValue().length() >= 32) {
                return cookie.getValue();
            }
        }
        throw new CustodyUnauthorizedException("session cookie required");
    }

    static void set(HttpServletResponse response, String token, Duration ttl, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, ttl, secure).toString());
    }

    static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure).toString());
    }

    private static ResponseCookie cookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/custody")
                .maxAge(maxAge)
                .build();
    }
}
