package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyRepository;
import org.springframework.stereotype.Service;

/**
 * 托管安全维护服务，负责清理过期会话、幂等键和 API nonce。
 */
@Service
public class CustodySecurityMaintenanceService {
    /** 托管数据仓储。 */
    private final CustodyRepository repository;

    /** 构造安全维护服务。 */
    public CustodySecurityMaintenanceService(CustodyRepository repository) {
        this.repository = repository;
    }

    /** 清理过期安全数据并返回删除行数。 */
    public int cleanupExpiredSecurityRows() {
        return repository.cleanupExpiredSecurityRows();
    }
}
