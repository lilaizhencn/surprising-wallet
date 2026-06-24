package com.surprising.wallet.service.service;

import com.surprising.wallet.common.pojo.UserAsset;

import java.math.BigDecimal;

/**
 * Legacy user_asset mirror for compatibility with external APIs and old
 * notification consumers. Runtime balance source of truth is ledger_balance.
 */
@Deprecated
public interface UserAssetService {

    UserAsset getOrCreate(Long userId, Integer currency);

    boolean addBalance(Long userId, Integer currency, BigDecimal amount);

    boolean freeze(Long userId, Integer currency, BigDecimal amount);

    boolean deduct(Long userId, Integer currency, BigDecimal amount);

    boolean unfreeze(Long userId, Integer currency, BigDecimal amount);
}
