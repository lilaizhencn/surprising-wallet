package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */

@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ScriptPubKey implements Serializable {


    /**
     * 保存 {@code serialVersionUID}，用于标识交易、区块或业务记录。
     */
    private static final long serialVersionUID = 5808212639641271980L;

    /**
     * 保存 {@code asm}，用于承载当前对象的运行配置或业务数据。
     */
    private String asm;
    /**
     * 保存 {@code hex}，用于承载当前对象的运行配置或业务数据。
     */
    private String hex;
    /**
     * 保存 {@code reqSigs}，用于承载当前对象的运行配置或业务数据。
     */
    private int reqSigs;
    /**
     * 保存 {@code type}，用于承载当前对象的运行配置或业务数据。
     */
    private String type;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;
    /**
     * 保存 {@code addresses}，表示链、网络、资产或代币配置。
     */
    private List<String> addresses;
    /**
     * 兼容比特币现金api响应
     */
    private List<String> cashAddrs;

    /**
     * 获取或查询 {@code getSerialversionuid} 对应的数据，供调用方读取当前状态。
     */
    public static long getSerialversionuid() {
        return ScriptPubKey.serialVersionUID;
    }

    /**
     * 解析或转换 {@code convert} 对应的数据，并校验其格式和边界。
     */
    public static ScriptPubKey convert(String jsonString) {
        return JSON.parseObject(jsonString, ScriptPubKey.class);
    }


}
