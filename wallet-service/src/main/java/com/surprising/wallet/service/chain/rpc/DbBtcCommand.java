package com.surprising.wallet.service.chain.rpc;

import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Component;

@Component
public
class DbBtcCommand extends DbBtcLikeJsonRpcCommand implements BtcCommand {
    public DbBtcCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {        super("BTC", repository, rpcNodeService);
    }
}
