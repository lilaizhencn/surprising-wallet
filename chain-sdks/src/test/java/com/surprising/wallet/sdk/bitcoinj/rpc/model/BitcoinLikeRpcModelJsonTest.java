package com.surprising.wallet.sdk.bitcoinj.rpc.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 Bitcoin-like RPC 模型使用 Jackson 3 解析和序列化时的字段兼容性。
 */
class BitcoinLikeRpcModelJsonTest {

    /**
     * 验证 ScriptSig 能解析已知字段并忽略 RPC 可能追加的未知字段。
     */
    @Test
    void shouldParseScriptSigAndIgnoreUnknownFields() {
        ScriptSig scriptSig = ScriptSig.convert("{\"asm\":\"OP_CHECKSIG\",\"hex\":\"51\",\"futureField\":true}");

        assertEquals("OP_CHECKSIG", scriptSig.getAsm());
        assertEquals("51", scriptSig.getHex());
    }

    /**
     * 验证 ScriptSig 序列化时保留有效字段并排除空字段，避免改变原有 RPC 模型语义。
     */
    @Test
    void shouldSerializeScriptSigWithoutNullFields() {
        ScriptSig scriptSig = new ScriptSig();
        scriptSig.setAsm("OP_CHECKSIG");

        String json = scriptSig.toString();
        ScriptSig restored = ScriptSig.convert(json);

        assertEquals("OP_CHECKSIG", restored.getAsm());
        assertNull(restored.getHex());
        assertFalse(json.contains("\"hex\""));
    }

    /**
     * 验证 ScriptPubKey 的数值、列表和 BCH 兼容地址字段能够完整解析。
     */
    @Test
    void shouldParseScriptPubKeyFields() {
        ScriptPubKey scriptPubKey = ScriptPubKey.convert("{"
                + "\"asm\":\"OP_DUP OP_HASH160\","
                + "\"hex\":\"76a9\","
                + "\"reqSigs\":1,"
                + "\"type\":\"pubkeyhash\","
                + "\"address\":\"legacy-address\","
                + "\"addresses\":[\"legacy-address\"],"
                + "\"cashAddrs\":[\"bitcoincash:q-address\"],"
                + "\"futureField\":\"ignored\""
                + "}");

        assertEquals("OP_DUP OP_HASH160", scriptPubKey.getAsm());
        assertEquals("76a9", scriptPubKey.getHex());
        assertEquals(1, scriptPubKey.getReqSigs());
        assertEquals("pubkeyhash", scriptPubKey.getType());
        assertEquals("legacy-address", scriptPubKey.getAddress());
        assertEquals(List.of("legacy-address"), scriptPubKey.getAddresses());
        assertEquals(List.of("bitcoincash:q-address"), scriptPubKey.getCashAddrs());
    }
}
