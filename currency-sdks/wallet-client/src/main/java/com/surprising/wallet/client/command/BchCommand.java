package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

@RpcConfig(
        server = "${atomex.bch.server}",
        username = "${atomex.bch.server.user}",
        password = "${atomex.bch.server.pwd}"
)
public interface BchCommand extends BtcLikeCommand {
}
