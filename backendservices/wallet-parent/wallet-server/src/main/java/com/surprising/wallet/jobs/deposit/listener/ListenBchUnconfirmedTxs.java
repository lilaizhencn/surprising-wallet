package com.surprising.wallet.jobs.deposit.listener;

import com.google.common.net.InetAddresses;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.service.service.TransactionService;
import com.surprising.wallet.service.wallet.impl.BchWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoincashj.core.*;
import org.bitcoincashj.core.listeners.OnTransactionBroadcastListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * @author atomex
 */
@Slf4j
@Component
public class ListenBchUnconfirmedTxs {
    @Autowired
    BchWallet bchWallet;

    @Autowired
    TransactionService txService;

    @Value("${atomex.app.env.name}")
    private String env;
    @Value("${atomex.bch.server}")
    private String bchServer;

    @PostConstruct
    public void init() {
        try {
            if ("dev".equals(env)) {
                return;
            }
            log.info("开始监听bch未确认的交易");
            NetworkParameters params = bchWallet.getBchNetworkParameters();
            PeerGroup peer = new PeerGroup(params);
            peer.addOnTransactionBroadcastListener(new UnconfirmedBchTxListener());
            String addr = bchServer.split("//")[1];
            peer.addAddress(new PeerAddress(params, InetAddresses.forString(addr.split(":")[0]), params.getPort()));
            peer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("开始停止监听bch未确认交易");
                peer.stop();
                log.info("已停止监听bch未确认交易");
            }));
        } catch (Exception e) {
            log.error("监听bch未确认交易异常", e);
        }
    }

    class UnconfirmedBchTxListener implements OnTransactionBroadcastListener {
        @Override
        public void onTransaction(Peer peer, Transaction tx) {
            try {
                List<TransactionDTO> transactionDTOS = bchWallet.convertFromBchBjTx(tx);
                if (CollectionUtils.isEmpty(transactionDTOS)) {
                    return;
                }
                ListenBchUnconfirmedTxs.log.info("receive bch unconfirmed tx:{}", tx.getHashAsString());
                txService.saveTransaction(transactionDTOS);
            } catch (Throwable e) {
                ListenBchUnconfirmedTxs.log.error("receive unconfirmed tx error, tx:{}", tx.getHashAsString(), e);
            }
        }
    }
}
