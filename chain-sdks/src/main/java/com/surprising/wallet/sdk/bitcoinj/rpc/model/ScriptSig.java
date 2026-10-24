package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;


@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ScriptSig implements Serializable {


    /**
     * 使用 Jackson 3 处理 Bitcoin-like RPC 返回的 JSON 数据。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final long serialVersionUID = 6500371981011623277L;

    private String asm;
    private String hex;

    public static ScriptSig convert(final String jsonString) {
        return OBJECT_MAPPER.readValue(jsonString, ScriptSig.class);
    }

    public String getAsm() {
        return this.asm;
    }

    public void setAsm(final String asm) {
        this.asm = asm;
    }

    public String getHex() {
        return this.hex;
    }

    public void setHex(final String hex) {
        this.hex = hex;
    }

    @Override
    public String toString() {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

}
