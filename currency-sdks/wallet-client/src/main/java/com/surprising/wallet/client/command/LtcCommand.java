package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * Litecoin Core-compatible JSON-RPC command interface.
 */
@RpcConfig(
        server = "${atomex.ltc.server}",
        username = "${atomex.ltc.server.user}",
        password = "${atomex.ltc.server.pwd}"
)
public interface LtcCommand extends BtcLikeCommand {
}
