package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import java.util.Locale;

/** 三类配置入口共用的管理员校验、部分更新和字符串规范化。 */
final class WalletConfigSupport {
    private WalletConfigSupport() { }
    static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin() || actor.tenantId() != null) {
            throw new CustodyForbiddenException("platform administrator access is required");
        }
    }

    static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    static void required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }

    static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    static String normalizeLower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    static String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }

    static String lower(String value) { return value == null ? null : value.trim().toLowerCase(Locale.ROOT); }

    static int intValue(Integer value) { return value == null ? 0 : value; }

    static long longValue(Long value) { return value == null ? 0 : value; }

    static <T> T or(T requested, T current) { return requested == null ? current : requested; }

    static int or(Integer requested, Integer current, int fallback) {
        if (requested != null) return requested;
        return current != null ? current : fallback;
    }

    static String or(String requested, String current, String fallback) {
        if (requested != null) return requested;
        return current != null ? current : fallback;
    }

    static long or(Long requested, Long current, long fallback) {
        if (requested != null) return requested;
        return current != null ? current : fallback;
    }

    static boolean or(Boolean requested, Boolean current, boolean fallback) {
        if (requested != null) return requested;
        return current != null ? current : fallback;
    }
}
