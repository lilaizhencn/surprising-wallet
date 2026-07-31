package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;

import java.io.Serializable;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */

@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ScriptSig implements Serializable {


    /**
     * 保存 {@code serialVersionUID}，用于标识交易、区块或业务记录。
     */
    private static final long serialVersionUID = 6500371981011623277L;

    /**
     * 保存 {@code asm}，用于承载当前对象的运行配置或业务数据。
     */
    private String asm;
    /**
     * 保存 {@code hex}，用于承载当前对象的运行配置或业务数据。
     */
    private String hex;

    /**
     * 解析或转换 {@code convert} 对应的数据，并校验其格式和边界。
     */
    public static ScriptSig convert(final String jsonString) {
        return JSON.parseObject(jsonString, ScriptSig.class);
    }

    /**
     * 获取或查询 {@code getAsm} 对应的数据，供调用方读取当前状态。
     */
    public String getAsm() {
        return this.asm;
    }

    /**
     * 设置或更新 {@code setAsm} 对应的状态，并保持相关业务字段一致。
     */
    public void setAsm(final String asm) {
        this.asm = asm;
    }

    /**
     * 获取或查询 {@code getHex} 对应的数据，供调用方读取当前状态。
     */
    public String getHex() {
        return this.hex;
    }

    /**
     * 设置或更新 {@code setHex} 对应的状态，并保持相关业务字段一致。
     */
    public void setHex(final String hex) {
        this.hex = hex;
    }

    /**
     * 将对象转换为便于日志记录和排障的字符串表示。
     */
    @Override
    public String toString() {
        return JSON.toJSONString(this);
    }

}
