package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyApiNonceRepository;
import com.surprising.wallet.repository.CustodySessionRepository;
import com.surprising.wallet.repository.CustodyIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 清理过期会话、幂等键和 API nonce。 */
@Service
@RequiredArgsConstructor
public class CustodySecurityMaintenanceService {
    private final CustodyApiNonceRepository nonces;
    private final CustodySessionRepository sessions;
    private final CustodyIdempotencyRepository idempotencies;

    public int cleanupExpiredSecurityRows() {
        return nonces.deleteExpired() + sessions.deleteExpired() + idempotencies.deleteExpired();
    }
}
