package com.surprising.wallet.service.service.impl;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
@Slf4j
@Service
public class AddressServiceImpl implements AddressService {

    @Autowired
    private ChainJdbcRepository chainJdbcRepository;

    private Address toAddress(ChainAddressRecord record, AssetRuntimeMetadata currency) {
        return Address.builder()
                .userId(record.getUserId())
                .address(record.getAddress())
                .network("")
                .scriptType("")
                .redeemScript("")
                .witnessScript("")
                .derivationPath(record.getDerivationPath())
                .publicKeys("")
                .balance(BigDecimal.ZERO)
                .biz(record.getBiz())
                .nonce(0)
                .index(Math.toIntExact(record.getAddressIndex()))
                .status((byte) Constants.WAITING)
                .currency(currency.getName())
                .build();
    }

    private Optional<Address> findCaseFoldedChainAddress(AssetRuntimeMetadata currency, String addressStr) {
        if (!"ETH".equalsIgnoreCase(currency.chain())) {
            return Optional.empty();
        }
        String normalized = addressStr.toLowerCase(Locale.ROOT);
        if (normalized.equals(addressStr)) {
            return Optional.empty();
        }
        return chainJdbcRepository.findChainAddressByAddress(currency.chain(), currency.assetSymbol(), normalized)
                .map(record -> toAddress(record, currency));
    }

    @Override
    public Address getAddress(String addressStr, AssetRuntimeMetadata currency) {
        if (!StringUtils.hasText(addressStr) || currency == null) {
            return null;
        }
        return findChainAddress(currency, addressStr).orElse(null);
    }

    private Optional<Address> findChainAddress(AssetRuntimeMetadata currency, String addressStr) {
        return chainJdbcRepository.findChainAddressByAddress(currency.chain(), currency.assetSymbol(), addressStr)
                .map(record -> toAddress(record, currency))
                .or(() -> findCaseFoldedChainAddress(currency, addressStr));
    }
}
