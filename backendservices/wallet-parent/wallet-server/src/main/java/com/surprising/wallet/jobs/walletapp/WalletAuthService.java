package com.surprising.wallet.jobs.walletapp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalletAuthService {
    public WalletUser requireUser(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "exchange login required");
        }

        long userId;
        try {
            userId = Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid exchange user id", ex);
        }
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid exchange user id");
        }

        String displayName = trimTo(firstPresent(
                request.getHeader("X-User-Name"),
                request.getHeader("X-Username"),
                "EX-" + userId), 64);
        String email = trimTo(firstPresent(
                request.getHeader("X-User-Email"),
                request.getHeader("X-Email"),
                "exchange-user-" + userId + "@surprising.local"), 128);
        return new WalletUser(userId, email, displayName);
    }

    public void logout(HttpServletRequest request) {
        // Authentication is owned by Surprising EX. Wallet keeps no user session state.
    }

    private static String firstPresent(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record WalletUser(long id, String email, String displayName) {
    }
}
