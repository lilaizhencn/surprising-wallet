package com.surprising.wallet.common.pojo.rpc;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;

import java.io.Serializable;

/**
 * @author lilaizhen
 * <p>
 * "scriptSig" : {
 * "asm" :
 * "3046022100c86f5b4c2d7c32825a9f5e4bd9ba195d7c1d6c02c5c1f4e05585f043ccfe4663022100ff1d5cbbeaf36233a5eca0a060cb83bf52670b6bb09eb4662b35252568e3070601
 * 04cdacf9ec1bff781d338487549c8a37c2fc36a3b61424249d15fda37494c15f77b88f5f48283e6409db4b075464820d91dcdb8751a4c4ee9eaa7a5c5dd1350a75",
 * "hex" :
 * "493046022100c86f5b4c2d7c32825a9f5e4bd9ba195d7c1d6c02c5c1f4e05585f043ccfe4663022100ff1d5cbbeaf36233a5eca0a060cb83bf52670b6bb09eb4662b35252568e30706014104cdacf9ec1bff781d338487549c8a37c2fc36a3b61424249d15fda37494c15f77b88f5f48283e6409db4b075464820d91dcdb8751a4c4ee9eaa7a5c5dd1350a75"
 * },
 */

@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ScriptSig implements Serializable {

    /**
     *
     */
    private static final long serialVersionUID = 6500371981011623277L;

    private String asm;
    private String hex;

    public static ScriptSig convert(final String jsonString) {
        return JSON.parseObject(jsonString, ScriptSig.class);
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
        return JSON.toJSONString(this);
    }

}
