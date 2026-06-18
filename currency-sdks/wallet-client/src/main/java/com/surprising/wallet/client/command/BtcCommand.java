package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
@RpcConfig(
        server = "${atomex.btc.server}",
        username = "${atomex.btc.server.user}",
        password = "${atomex.btc.server.pwd}"
)
public interface BtcCommand extends BtcLikeCommand {

}
