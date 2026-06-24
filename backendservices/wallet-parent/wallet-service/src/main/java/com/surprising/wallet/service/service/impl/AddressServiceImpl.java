package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.dao.AddressRepository;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 服务实现
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
@Slf4j
@Service
public class AddressServiceImpl
        extends AbstractCrudService<AddressRepository, Address, AddressExample, Integer>
        implements AddressService {

    @Autowired
    private AddressRepository addressRepos;
    @Autowired
    private ChainJdbcRepository chainJdbcRepository;
    @Autowired
    private AssetRoutingService assetRoutingService;

    @Override
    protected AddressExample getPageExample(String fieldName, String keyword) {
        AddressExample example = new AddressExample();
        example.createCriteria().andFieldLike(fieldName, keyword);
        return example;
    }

    @Override
    public Address getAndLockOneByExample(AddressExample example, ShardTable table) {
        Address address = addressRepos.selectAndLockOneByExample(example, table);
        if (!ObjectUtils.isEmpty(address)) {
            address.setCurrency(table.getPrefix());
        }
        return address;
    }

    @Override
    public Address getAddress(String addressStr, ShardTable table) {
        if (!StringUtils.hasText(addressStr)) {
            return null;
        }
        RuntimeAsset parsedCurrency = RuntimeAsset.parseName(table.getPrefix());
        final RuntimeAsset currency = assetRoutingService.legacyMainCurrency(parsedCurrency);
        table = ShardTable.builder().prefix(currency.getName()).build();

        if (isUnifiedBitcoinLike(currency)) {
            String chain = assetRoutingService.requireChainForRuntimeCurrencyId(currency.getIndex());
            return chainJdbcRepository.findChainAddressByAddress(chain, addressStr)
                    .map(record -> toAddress(record, currency))
                    .orElse(null);
        }

        ShardTable legacyTable = ShardTable.builder().prefix(currency.getName()).build();
        AddressExample example = new AddressExample();
        example.createCriteria().andAddressEqualTo(addressStr);
        Optional<Address> oneByExample = getOneByExample(example, legacyTable);
        oneByExample.ifPresent(address -> address.setCurrency(currency.getName()));
        return oneByExample.orElse(null);
    }

    private Address toAddress(ChainAddressRecord record, RuntimeAsset currency) {
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

    private boolean isUnifiedBitcoinLike(RuntimeAsset currency) {
        return assetRoutingService.isBitcoinLikeRuntimeCurrency(currency);
    }

    @Override
    public Address getAddress(String addressStr, RuntimeAsset currency) {
        if (!StringUtils.hasText(addressStr)) {
            return null;
        }
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        return getAddress(addressStr, table);
    }


    @Override
    public int countByExam(AddressExample example, ShardTable table) {
        return dao.countByExample(example, table);
    }

    @Override
    public BigDecimal getTotalBalance(AddressExample example, ShardTable table) {
        return dao.getTotalBalance(example, table);
    }

}
