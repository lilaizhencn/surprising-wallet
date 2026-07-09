package com.surprising.wallet.service.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;

/**
 * 服务接口
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
public interface AddressService {
    Address getAddress(String addressStr, AssetRuntimeMetadata currency);
}
