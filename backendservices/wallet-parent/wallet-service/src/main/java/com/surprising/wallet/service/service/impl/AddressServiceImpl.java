package com.surprising.wallet.service.service.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.AbstractCrudService;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.dao.AddressRepository;
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
        CurrencyEnum currency = CurrencyEnum.parseName(table.getPrefix());
        currency = CurrencyEnum.toMainCurrency(currency);
        table = ShardTable.builder().prefix(currency.getName()).build();
        AddressExample example = new AddressExample();
        example.createCriteria().andAddressEqualTo(addressStr);
        Optional<Address> oneByExample = getOneByExample(example, table);
        if (oneByExample.isPresent()) {
            Address address = oneByExample.get();
            address.setCurrency(currency.getName());
            return address;
        }
        return null;
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