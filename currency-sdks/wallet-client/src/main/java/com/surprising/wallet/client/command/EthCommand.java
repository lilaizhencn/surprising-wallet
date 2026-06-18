package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@RpcConfig(server = "${atomex.eth.server}")
public interface EthCommand extends EthLikeCommand {

}
