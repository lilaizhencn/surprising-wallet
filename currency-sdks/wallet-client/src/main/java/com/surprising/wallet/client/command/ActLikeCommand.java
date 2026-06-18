package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;

/**
 * @author lilaizhen
 * @data 2018/5/10
 */

public interface ActLikeCommand extends IRpcCommand {
    /**
     * 获取指定地址的余额
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_list_address_balances", required = true)
    JSONArray getAddressBalance(String address);

    /**
     * 获取最新区块高度
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_block_count", required = true)
    long getBlockCount(String placeholder);

    /**
     * 获取区块基本信息
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_block", required = true)
    JSONObject getBlockByHeight(long height);

    /**
     * 根据txid获取交易详情
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_transaction", required = true)
    JSONArray getTransactionByTxId(String txId);

    /**
     * 根据txid获取act交易详情
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_pretty_transaction", required = true)
    JSONObject getPrettyTransactionByTxId(String txId);

    /**
     * 根据txid获取合约交易详情
     *
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_pretty_contract_transaction", required = true)
    JSONObject getPrettyContractTransactionByTxId(String txId);


    /**
     * 发送原始交易
     *
     * @param hex
     * @return
     */
    @JsonRpcMethod(value = "network_broadcast_transaction", required = true)
    String sendRawTransaction(JSONObject hex);

    /**
     * 根据交易获取txid
     *
     * @param hex
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_transaction_id", required = true)
    String getTransactionId(JSONObject hex);

    /**
     * 获得代币余额
     *
     * @param
     * @return
     */
    @JsonRpcMethod(value = "call_contract_local_emit", required = true)
    JSONArray getContractBalance(String contract, String caller, String method, String address);

    /**
     * wallet-open
     *
     * @param
     * @return
     */
    @JsonRpcMethod(value = "wallet_open", required = true)
    String walletOpen(String wallet);

    /**
     * wallet-open
     *
     * @param
     * @return
     */
    @JsonRpcMethod(value = "wallet_unlock", required = true)
    String walletUnlock(int timeout, String password);


    /**
     * wallet-open
     *
     * @param
     * @return
     */
    @JsonRpcMethod(value = "blockchain_get_events", required = true)
    JSONArray getEvents(long height, String resultId);


}
