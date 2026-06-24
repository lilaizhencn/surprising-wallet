package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.EthCommand;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public class EthWallet extends AbstractEthLikeWallet implements IWallet {
    @Autowired
    EthCommand command;

    @Autowired
    com.surprising.wallet.service.wallet.impl.Erc20Wallet erc20Wallet;

    @Value("${atomex.eth.withdraw.address}")
    private String ethWithdrawAddress;

    @PostConstruct
    public void init() {
        RESERVED = new BigDecimal("0.1");
        super.setCommand(command);
        super.setWithdrawAddress(ethWithdrawAddress);
    }

    @Override
    public void updateTXConfirmation(RuntimeAsset currency) {
        super.updateTXConfirmation(RuntimeAsset.ETH);
        RuntimeAsset.ERC20_SET.parallelStream().forEach(super::updateTXConfirmation);
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
        return RuntimeAsset.ETH;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    @Override
    public BigDecimal getDecimal() {
        return RuntimeAsset.ETH.getDecimal();
    }
}
