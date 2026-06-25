package com.surprising.wallet.service.chain.rpc;

import com.surprising.wallet.client.command.BchCommand;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Component;

@Component
public class DbBchCommand extends DbBtcLikeJsonRpcCommand implements BchCommand {
    public DbBchCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        super("BCH", repository, rpcNodeService);
    }
}
