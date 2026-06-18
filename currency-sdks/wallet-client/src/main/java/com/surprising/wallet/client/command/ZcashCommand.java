package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @date 2018/11/28
 */
@RpcConfig(
        server = "${atomex.zcash.server}",
        username = "${atomex.zcash.server.user}",
        password = "${atomex.zcash.server.pwd}"
)
public interface ZcashCommand extends BtcLikeCommand {

}
