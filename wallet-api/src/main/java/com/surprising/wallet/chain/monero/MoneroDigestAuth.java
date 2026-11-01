package com.surprising.wallet.chain.monero;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared Digest encoding for public daemon reads and the development funder. */
final class MoneroDigestAuth {
    private MoneroDigestAuth() { }
    static String digestAuthorization(String method, URI uri, String username, String password, String challenge) {
        Map<String, String> values = parseDigestChallenge(challenge);
        String realm = values.getOrDefault("realm", "");
        String nonce = values.getOrDefault("nonce", "");
        String algorithm = values.getOrDefault("algorithm", "MD5");
        String qop = firstQop(values.getOrDefault("qop", "auth"));
        String opaque = values.get("opaque");
        String nc = "00000001";
        String cnonce = md5Hex(Long.toHexString(System.nanoTime()) + ":" + username + ":" + nonce)
                .substring(0, 16);
        String requestUri = uri.getRawPath();
        if (requestUri == null || requestUri.isBlank()) {
            requestUri = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            requestUri = requestUri + "?" + uri.getRawQuery();
        }

        String ha1 = md5Hex(trim(username) + ":" + realm + ":" + trim(password));
        if ("MD5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5Hex(ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = md5Hex(method + ":" + requestUri);
        String response = qop.isBlank()
                ? md5Hex(ha1 + ":" + nonce + ":" + ha2)
                : md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        StringBuilder header = new StringBuilder("Digest ");
        appendDigestPart(header, "username", trim(username), true);
        appendDigestPart(header, "realm", realm, true);
        appendDigestPart(header, "nonce", nonce, true);
        appendDigestPart(header, "uri", requestUri, true);
        appendDigestPart(header, "response", response, true);
        if (!algorithm.isBlank()) {
            appendDigestPart(header, "algorithm", algorithm, false);
        }
        if (!qop.isBlank()) {
            appendDigestPart(header, "qop", qop, false);
            appendDigestPart(header, "nc", nc, false);
            appendDigestPart(header, "cnonce", cnonce, true);
        }
        if (opaque != null && !opaque.isBlank()) {
            appendDigestPart(header, "opaque", opaque, true);
        }
        return header.toString();
    }
    private static Map<String, String> parseDigestChallenge(String challenge) {
        String value = challenge == null ? "" : challenge.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("digest")) {
            value = value.substring("digest".length()).trim();
        }
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index < value.length()) {
            while (index < value.length()
                    && (value.charAt(index) == ',' || Character.isWhitespace(value.charAt(index)))) {
                index++;
            }
            int equals = value.indexOf('=', index);
            if (equals < 0) {
                break;
            }
            String key = value.substring(index, equals).trim().toLowerCase(Locale.ROOT);
            index = equals + 1;
            String parsed;
            if (index < value.length() && value.charAt(index) == '"') {
                StringBuilder builder = new StringBuilder();
                index++;
                while (index < value.length()) {
                    char current = value.charAt(index++);
                    if (current == '"') {
                        break;
                    }
                    if (current == '\\' && index < value.length()) {
                        current = value.charAt(index++);
                    }
                    builder.append(current);
                }
                parsed = builder.toString();
            } else {
                int comma = value.indexOf(',', index);
                if (comma < 0) {
                    parsed = value.substring(index).trim();
                    index = value.length();
                } else {
                    parsed = value.substring(index, comma).trim();
                    index = comma + 1;
                }
            }
            result.put(key, parsed);
        }
        return result;
    }
    private static String firstQop(String qop) {
        String value = qop == null ? "" : qop.trim();
        if (value.isBlank()) {
            return "";
        }
        for (String part : value.split(",")) {
            if ("auth".equalsIgnoreCase(part.trim())) {
                return "auth";
            }
        }
        return value.split(",")[0].trim();
    }
    private static void appendDigestPart(StringBuilder builder, String key, String value, boolean quoted) {
        if (builder.length() > "Digest ".length()) {
            builder.append(", ");
        }
        builder.append(key).append('=');
        if (quoted) {
            builder.append('"').append(value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        } else {
            builder.append(value == null ? "" : value);
        }
    }
    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest unavailable", e);
        }
    }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
