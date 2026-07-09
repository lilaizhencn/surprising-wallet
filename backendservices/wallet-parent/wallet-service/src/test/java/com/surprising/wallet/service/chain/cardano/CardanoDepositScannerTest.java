package com.surprising.wallet.service.chain.cardano;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardanoDepositScannerTest {
    private static final String TOKEN_UNIT =
            "00000000000000000000000000000000000000000000000000000000" + "55534443";

    @Test
    void tracksNativeAndTokenAccountsSeparatelyForSameAddress() {
        CardanoDepositScanner scanner = new CardanoDepositScanner(null, new FakeRepository());
        TokenDefinition token = TokenDefinition.builder()
                .chain("ADA")
                .symbol("USDC")
                .contractAddress(TOKEN_UNIT)
                .decimals(6)
                .active(true)
                .build();

        Map<String, CardanoDepositScanner.TrackedCardanoAddress> addresses =
                scanner.trackedDepositAddresses(Map.of(CardanoAssetUnit.fromTokenContract(TOKEN_UNIT), token));

        CardanoDepositScanner.TrackedCardanoAddress tracked = addresses.get("addr");
        assertEquals("ada-account", tracked.nativeRecord().getAccountId());
        assertEquals("usdc-account", tracked.tokenRecordsByUnit().get(TOKEN_UNIT).getAccountId());
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        FakeRepository() {
            super(null);
        }

        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            String accountId = "ADA".equals(assetSymbol) ? "ada-account" : "usdc-account";
            return List.of(ChainAddressRecord.builder()
                    .chain("ADA")
                    .assetSymbol(assetSymbol)
                    .accountId(accountId)
                    .address("addr")
                    .walletRole("DEPOSIT")
                    .enabled(true)
                    .build());
        }
    }
}
