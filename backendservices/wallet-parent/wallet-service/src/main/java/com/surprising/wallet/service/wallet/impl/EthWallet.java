package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.EthCommand;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;

/**
 * @author atomex
 * @data 12/04/2018
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sw.legacy.account-wallet.enabled", havingValue = "true")
public class EthWallet extends AbstractEthLikeWallet implements IWallet {
    @Autowired
    EthCommand command;

    @Autowired
    com.surprising.wallet.service.wallet.impl.Erc20Wallet erc20Wallet;

    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        super.setCommand(command);
        super.setWithdrawAddress("");
        currency = loadRuntimeAssetByChain("ETH");
    }

    @Override
    public void updateTXConfirmation(RuntimeAsset currency) {
        super.updateTXConfirmation(getCurrency());
        loadRuntimeTokenAssets("ETH").parallelStream().forEach(super::updateTXConfirmation);
    }

    @Override
    public List<TransactionDTO> findRelatedTxs(Long height) {
        List<TransactionDTO> ethTransactions = super.findRelatedTxs(height);
        if (CollectionUtils.isEmpty(ethTransactions)) {
            ethTransactions = new LinkedList<>();
        }
        List<TransactionDTO> erc20Transactions = erc20Wallet.findRelatedTxs(height, this.height);
        if (!CollectionUtils.isEmpty(erc20Transactions)) {
            ethTransactions.addAll(erc20Transactions);
        }
        return ethTransactions;
    }

    //更新钱包中的币余额
    @Override
    public void updateTotalCurrencyBalance() {
        super.updateTotalCurrencyBalance();
        erc20Wallet.updateTotalCurrencyBalance();
    }


    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    @Override
    public BigDecimal getDecimal() {
        return getCurrency().getDecimal();
    }
}
