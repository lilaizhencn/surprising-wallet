package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.EtcCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@Slf4j
@Component
public class EtcWallet extends AbstractEthLikeWallet implements IWallet {
    @Autowired
    EtcCommand command;

    @Value("${atomex.etc.withdraw.address}")
    private String etcWithdrawAddress;

    @PostConstruct
    public void init() {
        RESERVED = new BigDecimal("0.1");
        super.setCommand(command);
        super.setWithdrawAddress(etcWithdrawAddress);

    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.ETC;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    @Override
    public BigDecimal getDecimal() {
        return CurrencyEnum.ETC.getDecimal();
    }
}
