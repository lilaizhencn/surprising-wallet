package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvmLogScannerTest {
    @Test
    void shouldDecodeErc20TransferLog() {
        Log log = new Log();
        log.setTransactionHash("0xtx");
        log.setTopics(List.of(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x0000000000000000000000001111111111111111111111111111111111111111",
                "0x0000000000000000000000002222222222222222222222222222222222222222"
        ));
        log.setData("0x000000000000000000000000000000000000000000000000000000000012d687");

        TokenDefinition usdt = TokenDefinition.builder()
                .chain("ETH")
                .symbol("USDT")
                .contractAddress("0x0000000000000000000000000000000000000001")
                .decimals(6)
                .standard("ERC20")
                .nativeAsset(false)
                .active(true)
                .build();

        List<DepositEvent> events = new EvmLogScanner().scanTransfers(ChainType.ETH, usdt, 11099971L, 12, List.of(log));

        assertEquals(1, events.size());
        assertEquals("0x1111111111111111111111111111111111111111", events.getFirst().fromAddress());
        assertEquals("0x2222222222222222222222222222222222222222", events.getFirst().toAddress());
        assertEquals(new BigDecimal("1.234567"), events.getFirst().amount());
    }
}
