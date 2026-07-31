package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Component
@Slf4j
public class LtcSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {
    /**
     * 获取或查询 {@code chain} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String chain() {
        return "LTC";
    }
}
