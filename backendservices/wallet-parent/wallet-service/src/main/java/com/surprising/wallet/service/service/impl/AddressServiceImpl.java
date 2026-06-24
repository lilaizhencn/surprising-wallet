package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.dao.AddressRepository;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
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
public class AddressServiceImpl
        extends AbstractCrudService<AddressRepository, Address, AddressExample, Integer>
        implements AddressService {

    @Autowired
    private AddressRepository addressRepos;
    @Autowired
    private ChainJdbcRepository chainJdbcRepository;

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
        CurrencyEnum parsedCurrency = CurrencyEnum.parseName(table.getPrefix());
        final CurrencyEnum currency = CurrencyEnum.toMainCurrency(parsedCurrency);
        table = ShardTable.builder().prefix(currency.getName()).build();

        if (isUnifiedBitcoinLike(currency)) {
            String chain = currency.getName().toUpperCase(Locale.ROOT);
            Optional<ChainAddressRecord> chainAddress =
                    chainJdbcRepository.findChainAddressByAddress(chain, addressStr);
            if (chainAddress.isPresent()) {
                Address address = legacyAddress(addressStr, currency)
                        .orElseGet(() -> toAddress(chainAddress.get(), currency));
                address.setCurrency(currency.getName());
                return address;
            }
        }

        Optional<Address> legacyAddress = legacyAddress(addressStr, currency);
        legacyAddress.ifPresent(address -> backfillChainAddress(currency, address));
        return legacyAddress.orElse(null);
    }

    private Optional<Address> legacyAddress(String addressStr, CurrencyEnum currency) {
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        AddressExample example = new AddressExample();
        example.createCriteria().andAddressEqualTo(addressStr);
        try {
            Optional<Address> oneByExample = getOneByExample(example, table);
            oneByExample.ifPresent(address -> address.setCurrency(currency.getName()));
            return oneByExample;
        } catch (DataAccessException e) {
            log.warn("legacy address table lookup failed for {} address {}", currency.getName(), addressStr, e);
            return Optional.empty();
        }
    }

    private Address toAddress(ChainAddressRecord record, CurrencyEnum currency) {
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
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }

    private void backfillChainAddress(CurrencyEnum currency, Address address) {
        if (!isUnifiedBitcoinLike(currency) || address.getUserId() == null
                || address.getBiz() == null || address.getIndex() == null) {
            return;
        }
        String chain = currency.getName().toUpperCase(Locale.ROOT);
        chainJdbcRepository.upsertChainAddress(ChainAddressRecord.builder()
                .chain(chain)
                .assetSymbol(chain)
                .accountId(address.getUserId().toString())
                .userId(address.getUserId())
                .biz(address.getBiz())
                .addressIndex(address.getIndex().longValue())
                .address(address.getAddress())
                .ownerAddress(null)
                .derivationPath(StringUtils.hasText(address.getDerivationPath())
                        ? address.getDerivationPath()
                        : String.format("m/44/%d/%d/%d/%d",
                        currency.getBip44CoinType(), address.getBiz(), address.getUserId(), address.getIndex()))
                .walletRole("DEPOSIT")
                .enabled(true)
                .build());
    }

    private boolean isUnifiedBitcoinLike(CurrencyEnum currency) {
        return currency == CurrencyEnum.BTC
                || currency == CurrencyEnum.LTC
                || currency == CurrencyEnum.DOGE
                || currency == CurrencyEnum.BCH;
    }

    @Override
    public Address getAddress(String addressStr, CurrencyEnum currency) {
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
