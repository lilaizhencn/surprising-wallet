package com.surprising.wallet.service.service;

import com.surprising.wallet.common.pojo.UserAsset;

import java.math.BigDecimal;

public interface UserAssetService {

    UserAsset getOrCreate(Long userId, Integer currency);

    boolean freeze(Long userId, Integer currency, BigDecimal amount);

    boolean deduct(Long userId, Integer currency, BigDecimal amount);

    boolean unfreeze(Long userId, Integer currency, BigDecimal amount);
}
