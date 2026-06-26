package com.surprising.wallet.jobs.walletapp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class WalletAuthService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_BITS = 256;
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;

    public WalletAuthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Throwable.class)
    public AuthPayload register(AuthRequest request, HttpServletRequest servletRequest) {
        String email = normalizeEmail(request.email());
        String password = normalizePassword(request.password());
        String displayName = normalizeDisplayName(request.displayName(), email);
        String passwordHash = hashPassword(password);
        try {
            Long userId = jdbcTemplate.queryForObject("""
                            insert into wallet_user(email, password_hash, display_name, status, created_at, updated_at)
                            values (?, ?, ?, 'ACTIVE', now(), now())
                            returning id
                            """,
                    Long.class, email, passwordHash, displayName);
            if (userId == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "wallet user registration failed");
            }
            return createSession(new WalletUser(userId, email, displayName), servletRequest);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public AuthPayload login(AuthRequest request, HttpServletRequest servletRequest) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                        select id, email, password_hash, display_name, status, failed_login_count, locked_until
                          from wallet_user
                         where email = ?
                        """, email);
        if (users.isEmpty()) {
            throw unauthorized();
        }
        Map<String, Object> row = users.get(0);
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status")))) {
            throw unauthorized();
        }
        Object lockedUntil = row.get("locked_until");
        if (lockedUntil instanceof java.sql.Timestamp timestamp
                && timestamp.toInstant().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "too many failed attempts, try again later");
        }
        long userId = ((Number) row.get("id")).longValue();
        if (!verifyPassword(password, String.valueOf(row.get("password_hash")))) {
            int failures = row.get("failed_login_count") instanceof Number number ? number.intValue() + 1 : 1;
            if (failures >= MAX_LOGIN_FAILURES) {
                jdbcTemplate.update("""
                                update wallet_user
                                   set failed_login_count = ?,
                                       locked_until = ?,
                                       updated_at = now()
                                 where id = ?
                                """, failures, java.sql.Timestamp.from(Instant.now().plus(LOCK_DURATION)), userId);
            } else {
                jdbcTemplate.update("""
                                update wallet_user
                                   set failed_login_count = ?,
                                       updated_at = now()
                                 where id = ?
                                """, failures, userId);
            }
            throw unauthorized();
        }
        jdbcTemplate.update("""
                        update wallet_user
                           set failed_login_count = 0,
                               locked_until = null,
                               last_login_at = now(),
                               updated_at = now()
                         where id = ?
                        """, userId);
        return createSession(new WalletUser(userId, email, String.valueOf(row.get("display_name"))), servletRequest);
    }

    public WalletUser requireUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet login required");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet login required");
        }
        String tokenHash = sha256Hex(token);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select u.id, u.email, u.display_name
                          from wallet_user_session s
                          join wallet_user u on u.id = s.user_id
                         where s.token_hash = ?
                           and s.revoked_at is null
                           and s.expires_at > now()
                           and u.status = 'ACTIVE'
                         limit 1
                        """, tokenHash);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet login required");
        }
        jdbcTemplate.update("""
                        update wallet_user_session
                           set last_seen_at = now()
                         where token_hash = ?
                        """, tokenHash);
        Map<String, Object> row = rows.get(0);
        return new WalletUser(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("email")),
                String.valueOf(row.get("display_name")));
    }

    @Transactional(rollbackFor = Throwable.class)
    public void logout(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (!token.isBlank()) {
            jdbcTemplate.update("""
                            update wallet_user_session
                               set revoked_at = now()
                             where token_hash = ? and revoked_at is null
                            """, sha256Hex(token));
        }
    }

    private AuthPayload createSession(WalletUser user, HttpServletRequest request) {
        String token = randomToken();
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        jdbcTemplate.update("""
                        insert into wallet_user_session(user_id, token_hash, expires_at, ip_address, user_agent,
                                                        created_at, last_seen_at)
                        values (?, ?, ?, ?, ?, now(), now())
                        """,
                user.id(),
                sha256Hex(token),
                java.sql.Timestamp.from(expiresAt),
                clientIp(request),
                trimTo(request.getHeader("User-Agent"), 300));
        return new AuthPayload(user, token, expiresAt.toString());
    }

    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid email is required");
        }
        return value;
    }

    private static String normalizePassword(String password) {
        String value = password == null ? "" : password;
        if (value.length() < 8 || value.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password must be 8 to 128 characters");
        }
        return value;
    }

    private static String normalizeDisplayName(String displayName, String email) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isBlank()) {
            value = email.substring(0, email.indexOf('@'));
        }
        return trimTo(value, 64);
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        return "pbkdf2_sha256$" + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PBKDF2_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("token hashing failed", e);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return trimTo(forwarded.split(",")[0].trim(), 64);
        }
        return trimTo(request.getRemoteAddr(), 64);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password");
    }

    public record AuthRequest(String email, String password, String displayName) {
    }

    public record WalletUser(long id, String email, String displayName) {
    }

    public record AuthPayload(WalletUser user, String token, String expiresAt) {
    }
}
