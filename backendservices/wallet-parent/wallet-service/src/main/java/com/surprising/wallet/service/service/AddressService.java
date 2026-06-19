package com.surprising.wallet.service.service;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.common.mybatis.sharding.service.CrudService;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.service.criteria.AddressExample;

import java.math.BigDecimal;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
public interface AddressService
        extends CrudService<Address, AddressExample, Integer> {
    Address getAndLockOneByExample(AddressExample example, ShardTable table);

    Address getAddress(String addressStr, ShardTable table);

    Address getAddress(String addressStr, CurrencyEnum currencyEnum);

    int countByExam(final AddressExample example, final ShardTable table);

    BigDecimal getTotalBalance(AddressExample example, ShardTable table);

}
