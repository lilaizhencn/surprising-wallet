package com.surprising.wallet.chain.evm;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** A real loopback HTTP oracle captures the bytes disclosed before an operator signature exists. */
class Evm7702UnsignedFeeTest {
    private static final long CHAIN=31337;
    private static final String ADDRESS="0x"+"ab".repeat(20);
    private final ObjectMapper json=new ObjectMapper();

    @Test void opOracleReceivesOnlyCanonicalUnsignedEnvelope() throws Exception { quote("op-stack",false); }
    @Test void scrollOracleReceivesUnsignedEnvelopeAndConservativeSignatureByteBudget() throws Exception { quote("scroll",true); }

    private void quote(String feeModel,boolean padding) throws Exception {
        byte[] unsigned=unsigned();var received=new ArrayList<byte[]>();
        var server=server(received);
        Web3j web3j=Web3j.build(new HttpService("http://127.0.0.1:"+server.getAddress().getPort()));
        try {
            assertEquals(BigInteger.valueOf(123),EvmFeeSupport.estimateUnsignedSeparateL1Fee(web3j,profile(feeModel),ADDRESS,unsigned));
            assertEquals(1,received.size());byte[] actual=received.getFirst();
            assertArrayEquals(unsigned,Arrays.copyOf(actual,unsigned.length));
            assertEquals(unsigned.length+(padding?68:0),actual.length);
            if(padding) {
                byte[] budget=new byte[68];Arrays.fill(budget,(byte)0xff);assertArrayEquals(budget,Arrays.copyOfRange(actual,unsigned.length,actual.length));
                assertThrows(RuntimeException.class,()->MpcEvmTransactionCodec.restore(actual,CHAIN));
            } else assertArrayEquals(unsigned,MpcEvmTransactionCodec.unsignedPayload(MpcEvmTransactionCodec.restore(actual,CHAIN),CHAIN));
        } finally {web3j.shutdown();server.stop(0);}
    }

    @Test void validSignedTransactionIsRejectedBeforeAnyOracleRequest() throws Exception {
        byte[] unsigned=unsigned();var fixture=new ECKey();
        byte[] signed=MpcEvmTransactionCodec.assemble(unsigned,CHAIN,fixture.getPubKey(),fixture.sign(Sha256Hash.wrap(org.web3j.crypto.Hash.sha3(unsigned))).encodeToDER());
        var received=new ArrayList<byte[]>();var server=server(received);
        Web3j web3j=Web3j.build(new HttpService("http://127.0.0.1:"+server.getAddress().getPort()));
        try {
            assertThrows(IllegalArgumentException.class,()->EvmFeeSupport.estimateUnsignedSeparateL1Fee(web3j,profile("op-stack"),ADDRESS,signed));
            assertTrue(received.isEmpty());
        } finally {web3j.shutdown();server.stop(0);}
    }

    private HttpServer server(List<byte[]> received) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            var request=json.readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("eth_call",request.path("method").asText());
            byte[] data=Numeric.hexStringToByteArray(request.path("params").get(0).path("data").asText());
            int offset=Numeric.toBigInt(Arrays.copyOfRange(data,4,36)).intValueExact()+4;
            int length=Numeric.toBigInt(Arrays.copyOfRange(data,offset,offset+32)).intValueExact();
            received.add(Arrays.copyOfRange(data,offset+32,offset+32+length));
            byte[] response=json.writeValueAsBytes(java.util.Map.of("jsonrpc","2.0","id",request.path("id").asLong(),"result",Numeric.toHexStringWithPrefixZeroPadded(BigInteger.valueOf(123),64)));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();return server;
    }
    private static AccountChainProfile profile(String feeModel) {
        return AccountChainProfile.builder().chain("ETH").network("mainnet").chainId(CHAIN).feeModel(feeModel).build();
    }
    private static byte[] unsigned() {
        var raw=MpcEvm7702Codec.batch(CHAIN,BigInteger.ZERO,BigInteger.ONE,BigInteger.TWO,BigInteger.valueOf(300_000),ADDRESS,"0x12345678",List.of());
        return MpcEvmTransactionCodec.unsignedPayload(raw,CHAIN);
    }
}
