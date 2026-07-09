package com.surprising.wallet.service.chain.rpc;

import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Component;

@Component
public class DbDogeCommand extends DbBtcLikeJsonRpcCommand implements DogeCommand {
    public DbDogeCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        super("DOGE", repository, rpcNodeService);
    }
}
