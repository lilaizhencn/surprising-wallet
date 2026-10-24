package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.util.List;


@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ScriptPubKey implements Serializable {


    /**
     * 使用 Jackson 3 解析 Bitcoin-like RPC 返回的 JSON 数据。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final long serialVersionUID = 5808212639641271980L;

    private String asm;
    private String hex;
    private int reqSigs;
    private String type;
    private String address;
    private List<String> addresses;
    /**
     * 兼容比特币现金api响应
     */
    private List<String> cashAddrs;

    public static long getSerialversionuid() {
        return ScriptPubKey.serialVersionUID;
    }

    public static ScriptPubKey convert(String jsonString) {
        return OBJECT_MAPPER.readValue(jsonString, ScriptPubKey.class);
    }


}
