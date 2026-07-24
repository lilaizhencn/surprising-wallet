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
 * 地址服务实现。
 *
 * 从链上地址仓库读取地址记录并转成通用地址模型，处理地址大小写归一化分支（如以太坊的 case-insensitive 匹配）。
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
@Slf4j
@Service
public class AddressServiceImpl implements AddressService {

    /**
     * 链地址仓库，负责按链名/资产标识查询钱包地址及用户绑定关系。
     */
    @Autowired
    private ChainJdbcRepository chainJdbcRepository;

    /**
     * 将数据库地址记录映射为业务可消费的 Address 模型。
     *
     * @param record   数据库地址记录
     * @param currency 资产运行时元数据
     * @return 通用地址模型
     */
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

    /**
     * 按以太坊地址忽略大小写规则做兜底匹配。
     *
     * 仅在主网查询未命中时触发，尝试将输入地址归一化为小写后再次查库，兼容链上校验不区分大小写场景。
     */
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

    /**
     * 获取指定链/资产下的地址信息。
     *
     * @param addressStr 以字符串形式输入的地址
     * @param currency   资产运行时元数据
     * @return 命中的地址模型；未命中返回 null
     */
    @Override
    public Address getAddress(String addressStr, AssetRuntimeMetadata currency) {
        if (!StringUtils.hasText(addressStr) || currency == null) {
            return null;
        }
        return findChainAddress(currency, addressStr).orElse(null);
    }

    /**
     * 按链和资产从链地址库精确匹配地址，必要时回退到大小写归一化匹配。
     */
    private Optional<Address> findChainAddress(AssetRuntimeMetadata currency, String addressStr) {
        return chainJdbcRepository.findChainAddressByAddress(currency.chain(), currency.assetSymbol(), addressStr)
                .map(record -> toAddress(record, currency))
                .or(() -> findCaseFoldedChainAddress(currency, addressStr));
    }
}
