package com.surprising.wallet.service.service.impl;

import com.surprising.wallet.common.pojo.UserAsset;
import com.surprising.wallet.service.dao.UserAssetRepository;
import com.surprising.wallet.service.service.UserAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
public class UserAssetServiceImpl implements UserAssetService {

    @Autowired
    private UserAssetRepository userAssetRepository;

    @Override
    @Transactional
    public UserAsset getOrCreate(Long userId, Integer currency) {
        UserAsset asset = userAssetRepository.selectByUserAndCurrency(userId, currency);
        if (asset == null) {
            asset = UserAsset.builder()
                    .userId(userId).currency(currency)
                    .balance(BigDecimal.ZERO).frozen(BigDecimal.ZERO)
                    .version(0).build();
            userAssetRepository.insert(asset);
        }
        return asset;
    }

    @Override
    @Transactional
    public boolean freeze(Long userId, Integer currency, BigDecimal amount) {
        UserAsset asset = getOrCreate(userId, currency);
        BigDecimal newBalance = asset.getBalance().subtract(amount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Insufficient balance userId={} currency={} balance={} amount={}", userId, currency, asset.getBalance(), amount);
            return false;
        }
        BigDecimal newFrozen = asset.getFrozen().add(amount);
        int rows = userAssetRepository.updateBalanceWithLock(asset.getId(),
                newBalance, newFrozen,
                asset.getBalance(), asset.getFrozen(), asset.getVersion());
        if (rows == 0) {
            log.warn("Optimistic lock failed for userId={} currency={}", userId, currency);
            return false;
        }
        return true;
    }

    @Override
    @Transactional
    public boolean deduct(Long userId, Integer currency, BigDecimal amount) {
        UserAsset asset = userAssetRepository.selectByUserAndCurrency(userId, currency);
        if (asset == null) return false;
        int rows = userAssetRepository.deductFrozen(asset.getId(), amount, asset.getVersion());
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean unfreeze(Long userId, Integer currency, BigDecimal amount) {
        UserAsset asset = userAssetRepository.selectByUserAndCurrency(userId, currency);
        if (asset == null) return false;
        BigDecimal newBalance = asset.getBalance().add(amount);
        BigDecimal newFrozen = asset.getFrozen().subtract(amount);
        if (newFrozen.compareTo(BigDecimal.ZERO) < 0) newFrozen = BigDecimal.ZERO;
        int rows = userAssetRepository.updateBalanceWithLock(asset.getId(),
                newBalance, newFrozen,
                asset.getBalance(), asset.getFrozen(), asset.getVersion());
        return rows > 0;
    }
}
