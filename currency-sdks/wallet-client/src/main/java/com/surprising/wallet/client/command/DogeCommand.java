package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * Dogecoin Core-compatible JSON-RPC command interface.
 */
@RpcConfig(
        server = "${atomex.doge.server}",
        username = "${atomex.doge.server.user}",
        password = "${atomex.doge.server.pwd}"
)
public interface DogeCommand extends BtcLikeCommand {
}
