package com.surprising.wallet.service.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;

/**
 * 地址服务接口。
 *
 * 负责根据链和资产上下文读取托管系统中的地址元数据，用于充值/提现的归属判断与风控分流。
 *
 * @author lilaizhen
 * @date 2018-03-27
 */
public interface AddressService {

    /**
     * 根据字符串地址查找对应地址元数据。
     *
     * @param addressStr 链上地址
     * @param currency   资产元数据上下文
     * @return 地址模型；未命中返回 null
     */
    Address getAddress(String addressStr, AssetRuntimeMetadata currency);
}
