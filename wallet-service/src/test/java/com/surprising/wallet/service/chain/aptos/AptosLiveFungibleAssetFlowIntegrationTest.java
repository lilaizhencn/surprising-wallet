package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosLiveFungibleAssetFlowIntegrationTest {
    private static final String TESTNET_USDC =
            "0x69091fbab5f7d635ee7ac5098cf0c1efbe31d68fec0f2cd565e8d168daf52832";
    private static final long OWNER_INDEX = 1_310_011L;
    private static final long EXTERNAL_INDEX = 1_310_012L;
    private static final long HOT_USER_ID = 6_110L;
    private static final long HOT_INDEX = 1_310_010L;

    @Test
    void liveFaDepositWithdrawCollectionAndReplayAreSafe() {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.fa.live.enabled"),
                "set -Daptos.fa.live.enabled=true after funding the derived Testnet accounts");
        String symbol = env("APTOS_FA_SYMBOL", "USDC").toUpperCase();
        String metadataAddress = env("APTOS_FA_METADATA", TESTNET_USDC);
        int decimals = Integer.parseInt(env("APTOS_FA_DECIMALS", "6"));
        long depositAtomic = 10L * pow10(decimals);
        long operationAtomic = pow10(decimals);
        long collectionAtomic = 2L * pow10(decimals);
        BigDecimal depositAmount = BigDecimal.TEN;
        BigDecimal operationAmount = BigDecimal.ONE;
        BigDecimal collectionAmount = new BigDecimal("2");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("APTOS_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("APTOS_DB_USER", "wallet"),
                env("APTOS_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        AptosRpcClient rpc = new AptosRpcClient(new ObjectMapper(),
                env("APTOS_RPC_URL", "https://fullnode.testnet.aptoslabs.com/v1"), "");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        AptosKeyService keys = masterSeed == null || masterSeed.isBlank()
                ? new AptosKeyService(new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER))
                : new AptosKeyService(masterSeed);
        AptosAddressService addresses = new AptosAddressService(keys, repository);
        AptosTransactionService transactions = new AptosTransactionService(
                rpc, new AptosTransactionSigner(keys), repository);
        AptosDepositScanner scanner = new AptosDepositScanner(rpc, repository);

        JsonNode metadata = rpc.fungibleAssetMetadata(metadataAddress).path("data");
        assertEquals(symbol, metadata.path("symbol").asText().toUpperCase());
        assertEquals(decimals, metadata.path("decimals").asInt());

        ChainAddressRecord external = external(keys, EXTERNAL_INDEX);
        String ownerAddress = address(keys, 6111L, OWNER_INDEX);
        requireFunding(rpc, external.getAddress(), ownerAddress, metadataAddress, depositAtomic);
        ChainAddressRecord owner = addresses.createTokenAddress(symbol, 6111, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord hot = addresses.createTokenAddress(
                symbol, HOT_USER_ID, 0, HOT_INDEX, "DEPOSIT");
        AptosTenantIntegrationFixture.TenantAddress tenantAddress =
                AptosTenantIntegrationFixture.attachDepositAddress(jdbc, owner);
        AptosTenantIntegrationFixture.attachPlatformAddress(jdbc, hot);

        long checkpoint = rpc.ledgerVersion();
        repository.updateScanHeight("APTOS", AptosDepositScanner.SCANNER, checkpoint, checkpoint);
        BigDecimal ledgerBefore = ledger(jdbc, tenantAddress.tenantId(), symbol, owner.getAccountId());

        String depositHash = transactions.sendFungibleAsset(
                external, metadataAddress, owner.getAddress(), depositAtomic);
        transactions.requireSuccessfulConfirmation(depositHash, Duration.ofMinutes(2));
        scanner.scanAndCredit();
        BigDecimal ledgerAfterDeposit = ledger(
                jdbc, tenantAddress.tenantId(), symbol, owner.getAccountId());
        assertAmountEquals(ledgerBefore.add(depositAmount), ledgerAfterDeposit);
        scanner.scanAndCredit();
        assertAmountEquals(ledgerAfterDeposit,
                ledger(jdbc, tenantAddress.tenantId(), symbol, owner.getAccountId()));

        String withdrawalNo = "aptos-fa-withdraw-" + UUID.randomUUID();
        assertEquals(1, repository.createTenantWithdrawalOrder(
                tenantAddress.tenantId(), withdrawalNo, owner.getUserId(), "APTOS", symbol,
                owner.getAddress(), owner.getAccountId(), external.getAddress(),
                operationAmount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(
                tenantAddress.tenantId(), "APTOS", symbol, owner.getAccountId(), operationAmount));
        repository.updateWithdrawalStatus(
                tenantAddress.tenantId(), "APTOS", withdrawalNo,
                "FROZEN", owner.getAddress(), null, null);
        assertEquals(1, repository.claimWithdrawalSigning(
                tenantAddress.tenantId(), "APTOS", withdrawalNo, owner.getAddress()));
        String withdrawalHash = transactions.sendFungibleAsset(
                owner, metadataAddress, external.getAddress(), operationAtomic);
        assertEquals(1, repository.markWithdrawalSent(
                tenantAddress.tenantId(), "APTOS", withdrawalNo, owner.getAddress(), withdrawalHash));
        assertEquals(withdrawalHash, repository.findWithdrawalTxHash(
                tenantAddress.tenantId(), "APTOS", withdrawalNo).orElseThrow());
        assertTrue(transactions.confirmWithdrawal(
                tenantAddress.tenantId(), withdrawalNo, symbol,
                owner.getAccountId(), operationAmount));

        String collectionNo = "aptos-fa-collection-" + UUID.randomUUID();
        assertEquals(1, repository.createCollectionRecord(
                tenantAddress.tenantId(), tenantAddress.custodyAddressId(), collectionNo,
                "APTOS", symbol, owner.getAddress(), hot.getAddress(),
                collectionAmount, BigDecimal.ZERO, null));
        String collectionHash = transactions.collectToken(
                tenantAddress.tenantId(), collectionNo, owner, metadataAddress,
                hot.getAddress(), BigDecimal.valueOf(collectionAtomic));
        assertEquals(collectionHash, transactions.collectToken(
                tenantAddress.tenantId(), collectionNo, owner, metadataAddress,
                hot.getAddress(), BigDecimal.valueOf(collectionAtomic)));
        assertTrue(transactions.confirmCollection(tenantAddress.tenantId(), collectionNo));

        assertAmountEquals(depositAmount.subtract(operationAmount),
                ledger(jdbc, tenantAddress.tenantId(), symbol, owner.getAccountId()));
        assertEquals(collectionAtomic, rpc.fungibleAssetBalance(hot.getAddress(), metadataAddress));
        assertEquals(depositAtomic - operationAtomic - collectionAtomic,
                rpc.fungibleAssetBalance(owner.getAddress(), metadataAddress));

        assertEquals(2L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where tx_hash in (?, ?) and status = 'CONFIRMED'
                   and asset_symbol = ? and raw_payload is not null
                """, Long.class, withdrawalHash, collectionHash, symbol));
        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where tx_hash = ? and status = 'CONFIRMED'
                   and asset_symbol = ? and raw_payload is not null
                """, Long.class, depositHash, symbol));
        assertEquals(3L, jdbc.queryForObject("""
                select count(*) from aptos_transaction
                 where asset_symbol = ?
                   and ((tx_hash = ? and amount = 10)
                     or (tx_hash = ? and amount = 1)
                     or (tx_hash = ? and amount = 2))
                """, Long.class, symbol, depositHash, withdrawalHash, collectionHash));

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                 where chain='APTOS'
                   and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));
        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from collection_record
                 where chain='APTOS' and tenant_id is null
                """, Long.class));
    }

    private static ChainAddressRecord external(AptosKeyService keys, long index) {
        Ed25519DerivedKey key = keys.derive(6112L, 0, index);
        String address = AptosKeyService.address(key.publicKey());
        return ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("APT")
                .accountId(address)
                .userId(6112L)
                .biz(0)
                .addressIndex(index)
                .address(address)
                .ownerAddress(address)
                .derivationPath(key.derivationPath())
                .walletRole("EXTERNAL")
                .enabled(true)
                .build();
    }

    private static String address(AptosKeyService keys, long userId, long index) {
        return AptosKeyService.address(keys.derive(userId, 0, index).publicKey());
    }

    private static void requireFunding(AptosRpcClient rpc, String external, String owner,
                                       String metadataAddress, long depositAtomic) {
        Assumptions.assumeTrue(rpc.aptBalance(external) > 5_000_000L,
                "external Testnet account needs APT gas: " + external);
        Assumptions.assumeTrue(rpc.aptBalance(owner) > 10_000_000L,
                "owner Testnet account needs APT gas: " + owner);
        Assumptions.assumeTrue(rpc.fungibleAssetBalance(external, metadataAddress) >= depositAtomic,
                "external Testnet account needs FA test funds: " + external);
    }

    private static BigDecimal ledger(JdbcTemplate jdbc, UUID tenantId,
                                     String symbol, String accountId) {
        BigDecimal balance = jdbc.queryForObject("""
                select coalesce(sum(total_balance), 0) from ledger_balance
                 where tenant_id = ? and chain = 'APTOS'
                   and asset_symbol = ? and account_id = ?
                """, BigDecimal.class, tenantId, symbol, accountId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    private static void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected amount " + expected.toPlainString()
                        + " but was " + actual.toPlainString());
    }

    private static long pow10(int decimals) {
        return BigDecimal.TEN.pow(decimals).longValueExact();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
