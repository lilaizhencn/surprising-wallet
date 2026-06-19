package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @data 2018/5/10
 */
@RpcConfig(
        server = "${atomex.ssc.server}",
        username = "${atomex.ssc.server.user}",
        password = "${atomex.ssc.server.pwd}"
)
public interface SSCCommand extends ActLikeCommand {

}
