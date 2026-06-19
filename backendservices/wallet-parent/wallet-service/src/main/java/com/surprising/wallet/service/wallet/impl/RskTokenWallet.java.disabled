package com.surprising.wallet.service.wallet.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.client.command.RbtcCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.http.HttpService;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author lilaizhen
 * @data 17/04/2018
 */
@Slf4j
@Component
public class RskTokenWallet extends Erc20Wallet implements IWallet {
    @Autowired
    RbtcCommand command;
    @Value("${atomex.rbtc.withdraw.address}")
    private String withdrawAddress;
    @Value("${atomex.rbtc.server}")
    private String rpcServerUrl;

    @Override
    @PostConstruct
    public void init() {
        RESERVED = BigDecimal.ZERO;
        super.setCommand(command);
        super.setWithdrawAddress(withdrawAddress);
        Web3jService web3jService = new HttpService(rpcServerUrl);
        web3j = Web3j.build(web3jService);
    }

    @Override
    public void updateTotalCurrencyBalance() {
        log.info("updateTotalCurrencyBalance rskToken begin");
        CurrencyEnum.RSK_TOKEN_ASSET.parallelStream().forEach((currency) -> {
            log.info("update {} total Balance begin", currency.getName());
            BigDecimal balance = getBalance(currency);
            updateTotalCurrencyBalance(currency, balance);
            log.info("update {} total Balance end", currency.getName());
        });
        log.info("updateTotalCurrencyBalance rskToken end");

    }

    @Override
    public List<TransactionDTO> findRelatedTxs(Long height, Long bestHeight) {
        RskTokenWallet.log.info("rskToken findRelatedTxs, height:{} begin", height);
        List<AccountTransaction> txs = CurrencyEnum.RSK_TOKEN_ASSET.stream()
                .map(currency -> super.findErc20Txs(height, bestHeight, currency))
                .filter((transactions) -> !CollectionUtils.isEmpty(transactions))
                .flatMap(List::parallelStream).collect(Collectors.toList());

        List<TransactionDTO> dtos = new LinkedList<>();
        if (!org.apache.commons.collections4.CollectionUtils.isEmpty(txs)) {
            dtos = txs.parallelStream()
                    .map(tx -> {
                        CurrencyEnum currency = CurrencyEnum.parseValue(tx.getCurrency());
                        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
                        accountTransactionService.addOnDuplicateKey(tx, table);
                        return convertAccountTxToDto(tx);
                    })
                    .collect(Collectors.toList());
        }

        RskTokenWallet.log.info("rskToken findRelatedTxs, height:{} end,size:{}", height, txs.size());
        return dtos;
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.RSK_TOKEN;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    @Override
    public BigDecimal getDecimal() {
        throw new RuntimeException("not support this getDecimal method");
    }

    @Override
    protected BigDecimal gas() {
        return new BigDecimal("60000").divide(CurrencyEnum.RBTC.getDecimal());
    }

    @Override
    protected BigDecimal gasPrice(CurrencyEnum currency) {
        return new BigDecimal("0.00000000005924");
    }

    @Override
    protected BigDecimal transferGasPrice(CurrencyEnum currency) {
        return new BigDecimal("0.00000000005924");
    }
}
