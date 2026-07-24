package com.surprising.wallet.chain.xrp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.xrpl.xrpl4j.client.faucet.FaucetClient;
import org.xrpl.xrpl4j.client.faucet.FundAccountRequest;
import org.xrpl.xrpl4j.crypto.keys.KeyPair;
import org.xrpl.xrpl4j.crypto.keys.Seed;
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.AccountSet;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrpTestnetFullFlowIntegrationTest {
    private static final String CHAIN = "XRP";
    private static final String RPC_URL = "https://s.altnet.rippletest.net:51234/";
    private static final String FAUCET_URL = "https://faucet.altnet.rippletest.net";
    private static final String USDC = "USDC";
    private static final String USDC_CURRENCY = "5553444300000000000000000000000000000000";
    private static final String USDT = "USDT";
    private static final String USDT_CURRENCY = "5553445400000000000000000000000000000000";
    private static final BcSignatureService SIGNATURES = new BcSignatureService();

    @Test
    void shouldExecuteNativeAndIssuedCurrencyFullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("xrp.live.flow.enabled"),
                "set -Dxrp.live.flow.enabled=true to broadcast XRPL Testnet transactions");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        UUID tenantId = XrpTenantIntegrationFixture.ensureTenant(jdbc);
        WalletKeyMaterialProvider keyMaterial = new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER);
        XrpKeyService keys = new XrpKeyService(new PubKeyConfig(keyMaterial),
                new AccountSecp256k1KeyService(keyMaterial));
        XrpAddressService addresses = new XrpAddressService(keys, repository);
        XrpRpcClient rpc = new XrpRpcClient(new ObjectMapper(), RPC_URL);
        XrpTransactionService transactions = new XrpTransactionService(rpc, keys, repository);
        XrpDepositScanner scanner = new XrpDepositScanner(rpc, repository);
        FaucetClient faucet = FaucetClient.construct(HttpUrl.get(FAUCET_URL));

        long run = System.currentTimeMillis();
        long userId = 200_000L + run % 100_000L;
        String runId = "XRP-LIVE-" + run;
        ChainAddressRecord hot = addresses.createNativeAddress(0L, 0, 0L, "DEPOSIT");
        ChainAddressRecord user = addresses.createNativeAddress(userId, 1, 0L, "DEPOSIT");
        ChainAddressRecord external = addresses.createNativeAddress(userId + 1L, 1, 0L, "EXTERNAL");
        ChainAddressRecord userUsdc = addresses.createIssuedCurrencyAddress(USDC, userId, 1, 0L, "DEPOSIT");
        ChainAddressRecord externalUsdc = addresses.createIssuedCurrencyAddress(
                USDC, userId + 1L, 1, 0L, "EXTERNAL");
        XrpTenantIntegrationFixture.assignAddress(jdbc, hot);
        XrpTenantIntegrationFixture.assignAddress(jdbc, user);
        XrpTenantIntegrationFixture.assignAddress(jdbc, userUsdc);
        UUID custodyAddressId = XrpTenantIntegrationFixture.attachDepositAddress(jdbc, user);

        fund(faucet, rpc, hot.getAddress());
        fund(faucet, rpc, user.getAddress());
        fund(faucet, rpc, external.getAddress());

        List<DepositEvent> nativeDeposits = scanner.scanAndCredit();
        assertTrue(nativeDeposits.stream().anyMatch(event -> event.toAddress().equals(user.getAddress())
                && event.assetSymbol().equals(CHAIN)));
        BigDecimal faucetDeposit = ledger(repository, CHAIN, user.getAccountId()).getTotalBalance();
        assertTrue(faucetDeposit.compareTo(BigDecimal.ZERO) > 0);
        scanner.scanAndCredit();
        assertAmount(faucetDeposit, ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String nativeWithdrawal = runId + "-XRP-WD";
        withdrawNative(tenantId, repository, transactions, rpc, nativeWithdrawal, user, external,
                new BigDecimal("5"));
        LedgerBalanceRecord afterNativeWithdrawal = ledger(repository, CHAIN, user.getAccountId());
        assertAmount(faucetDeposit.subtract(new BigDecimal("5")), afterNativeWithdrawal.getTotalBalance());
        assertAmount(BigDecimal.ZERO, afterNativeWithdrawal.getLockedBalance());
        String nativeCollection = collect(tenantId, custodyAddressId, repository, transactions, rpc,
                runId + "-XRP-COLLECT", user, hot, null, new BigDecimal("10"));
        scanner.scanAndCredit();
        assertInternalCollectionNotCredited(jdbc, nativeCollection);
        assertAmount(faucetDeposit.subtract(new BigDecimal("5")),
                ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        Seed issuerSeed = Seed.secp256k1Seed();
        KeyPair issuer = issuerSeed.deriveKeyPair();
        issuerSeed.destroy();
        String issuerAddress = issuer.publicKey().deriveAddress().value();
        fund(faucet, rpc, issuerAddress);
        waitValidated(rpc, enableDefaultRipple(rpc, issuer));
        issuedCurrencyFlow(tenantId, custodyAddressId, jdbc, repository, addresses, transactions, scanner, rpc,
                issuer, issuerAddress, user, external, hot, USDC, USDC_CURRENCY, runId);
        issuedCurrencyFlow(tenantId, custodyAddressId, jdbc, repository, addresses, transactions, scanner, rpc,
                issuer, issuerAddress, user, external, hot, USDT, USDT_CURRENCY, runId);

        assertFalse(repository.freezeLedgerBalance(
                tenantId, CHAIN, USDC, user.getAccountId(), new BigDecimal("1000")));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where available_balance < 0 "
                + "or locked_balance < 0 or total_balance < 0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order where chain='XRP' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from collection_record where chain='XRP' "
                + "and status <> 'CONFIRMED'", Integer.class));
        issuer.privateKey().destroy();
    }

    private static void issuedCurrencyFlow(
            UUID tenantId, UUID custodyAddressId, JdbcTemplate jdbc, ChainJdbcRepository repository,
            XrpAddressService addresses, XrpTransactionService transactions, XrpDepositScanner scanner,
            XrpRpcClient rpc, KeyPair issuer, String issuerAddress,
            ChainAddressRecord user, ChainAddressRecord external, ChainAddressRecord hot,
            String symbol, String currency, String runId) {
        upsertMockToken(jdbc, issuerAddress, symbol, currency);
        TokenDefinition token = repository.findToken(CHAIN, symbol).orElseThrow();
        ChainAddressRecord userToken = addresses.createIssuedCurrencyAddress(
                symbol, user.getUserId(), user.getBiz(), user.getAddressIndex(), "DEPOSIT");
        ChainAddressRecord externalToken = addresses.createIssuedCurrencyAddress(
                symbol, external.getUserId(), external.getBiz(), external.getAddressIndex(), "EXTERNAL");
        XrpTenantIntegrationFixture.assignAddress(jdbc, userToken);

        assertTrue(transactions.ensureIssuedCurrencyDepositReady(userToken, symbol).trustLineReady());
        assertTrue(transactions.ensureIssuedCurrencyDepositReady(externalToken, symbol).trustLineReady());
        String depositHash = sendIssued(rpc, issuer, user.getAddress(), currency, new BigDecimal("100"));
        waitValidated(rpc, depositHash);
        List<DepositEvent> deposits = scanner.scanAndCredit();
        assertTrue(deposits.stream().anyMatch(event -> depositHash.equals(event.txId())
                && symbol.equals(event.assetSymbol())));
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());

        withdrawToken(tenantId, repository, transactions, rpc,
                runId + "-" + symbol + "-WD", user, external, token, new BigDecimal("20"));
        LedgerBalanceRecord afterWithdrawal = ledger(repository, symbol, user.getAccountId());
        assertAmount(new BigDecimal("80"), afterWithdrawal.getTotalBalance());
        assertAmount(BigDecimal.ZERO, afterWithdrawal.getLockedBalance());

        String collectionHash = collect(tenantId, custodyAddressId, repository, transactions, rpc,
                runId + "-" + symbol + "-COLLECT", user, hot, token, new BigDecimal("30"));
        scanner.scanAndCredit();
        assertInternalCollectionNotCredited(jdbc, collectionHash);
        assertAmount(new BigDecimal("80"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());
        assertIssuedBalance(rpc, user.getAddress(), issuerAddress, currency, new BigDecimal("50"));
        assertIssuedBalance(rpc, external.getAddress(), issuerAddress, currency, new BigDecimal("20"));
        assertIssuedBalance(rpc, hot.getAddress(), issuerAddress, currency, new BigDecimal("30"));
    }

    private static void withdrawNative(UUID tenantId, ChainJdbcRepository repository,
                                       XrpTransactionService transactions,
                                       XrpRpcClient rpc, String orderNo, ChainAddressRecord from,
                                       ChainAddressRecord to, BigDecimal amount) {
        createAndFreeze(tenantId, repository, orderNo, from, to, CHAIN, amount);
        String txHash = transactions.sendNative(from, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(
                tenantId, CHAIN, orderNo, from.getAddress(), txHash));
        waitValidated(rpc, txHash);
        assertTrue(transactions.confirmWithdrawal(tenantId,
                repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, CHAIN, from.getAccountId(), amount));
    }

    private static void withdrawToken(UUID tenantId, ChainJdbcRepository repository,
                                      XrpTransactionService transactions,
                                      XrpRpcClient rpc, String orderNo, ChainAddressRecord from,
                                      ChainAddressRecord to, TokenDefinition token, BigDecimal amount) {
        createAndFreeze(tenantId, repository, orderNo, from, to, token.getSymbol(), amount);
        String txHash = transactions.sendIssuedCurrency(from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(
                tenantId, CHAIN, orderNo, from.getAddress(), txHash));
        waitValidated(rpc, txHash);
        assertTrue(transactions.confirmWithdrawal(tenantId,
                repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, token.getSymbol(), from.getAccountId(), amount));
    }

    private static void createAndFreeze(UUID tenantId, ChainJdbcRepository repository, String orderNo,
                                        ChainAddressRecord from, ChainAddressRecord to,
                                        String symbol, BigDecimal amount) {
        assertEquals(1, repository.createTenantWithdrawalOrder(
                tenantId, orderNo, from.getUserId(), CHAIN, symbol,
                from.getAddress(), from.getAccountId(), to.getAddress(), amount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(tenantId, CHAIN, symbol, from.getAccountId(), amount));
        assertEquals(1, repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN",
                from.getAddress(), null, null));
        assertEquals(1, repository.claimWithdrawalSigning(
                tenantId, CHAIN, orderNo, from.getAddress()));
    }

    private static String collect(
            UUID tenantId, UUID custodyAddressId, ChainJdbcRepository repository,
            XrpTransactionService transactions, XrpRpcClient rpc, String collectionNo,
            ChainAddressRecord from, ChainAddressRecord hot, TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? CHAIN : token.getSymbol();
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, CHAIN, symbol,
                from.getAddress(), hot.getAddress(), amount, BigDecimal.ZERO, null));
        String txHash = token == null
                ? transactions.collectNative(tenantId, collectionNo, from, hot.getAddress(), amount)
                : transactions.collectIssuedCurrency(
                        tenantId, collectionNo, from, token, hot.getAddress(), amount);
        assertEquals(txHash, token == null
                ? transactions.collectNative(tenantId, collectionNo, from, hot.getAddress(), amount)
                : transactions.collectIssuedCurrency(
                        tenantId, collectionNo, from, token, hot.getAddress(), amount));
        waitValidated(rpc, txHash);
        assertTrue(transactions.confirmCollection(
                tenantId, repository.findProfileByChain(CHAIN).orElseThrow(), collectionNo));
        return txHash;
    }

    private static void assertInternalCollectionNotCredited(JdbcTemplate jdbc, String txHash) {
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from deposit_record
                 where chain = 'XRP' and tx_hash = ?
                """, Integer.class, txHash));
    }

    private static String sendIssued(XrpRpcClient rpc, KeyPair issuer, String destination,
                                     String currency, BigDecimal amount) {
        IssuedCurrencyAmount issued = IssuedCurrencyAmount.builder()
                .issuer(issuer.publicKey().deriveAddress())
                .currency(currency)
                .value(amount.stripTrailingZeros().toPlainString())
                .build();
        Payment payment = Payment.builder()
                .account(issuer.publicKey().deriveAddress())
                .destination(Address.of(destination))
                .amount(issued)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(rpc.feeDrops())))
                .sequence(UnsignedInteger.valueOf(rpc.accountSequence(issuer.publicKey().deriveAddress().value())))
                .lastLedgerSequence(UnsignedInteger.valueOf(rpc.latestLedgerIndex() + 20L))
                .signingPublicKey(issuer.publicKey())
                .build();
        SingleSignedTransaction<Payment> signed = SIGNATURES.sign(issuer.privateKey(), payment);
        return rpc.submit(signed.signedTransactionBytes().hexValue());
    }

    private static String enableDefaultRipple(XrpRpcClient rpc, KeyPair issuer) {
        String issuerAddress = issuer.publicKey().deriveAddress().value();
        AccountSet accountSet = AccountSet.builder()
                .account(issuer.publicKey().deriveAddress())
                .setFlag(AccountSet.AccountSetFlag.DEFAULT_RIPPLE)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(rpc.feeDrops())))
                .sequence(UnsignedInteger.valueOf(rpc.accountSequence(issuerAddress)))
                .lastLedgerSequence(UnsignedInteger.valueOf(rpc.latestLedgerIndex() + 20L))
                .signingPublicKey(issuer.publicKey())
                .build();
        SingleSignedTransaction<AccountSet> signed = SIGNATURES.sign(issuer.privateKey(), accountSet);
        return rpc.submit(signed.signedTransactionBytes().hexValue());
    }

    private static void fund(FaucetClient faucet, XrpRpcClient rpc, String address) {
        if (rpc.accountInfo(address).isEmpty()) {
            faucet.fundAccount(FundAccountRequest.of(Address.of(address)));
            waitAccount(rpc, address);
        }
    }

    private static void waitAccount(XrpRpcClient rpc, String address) {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (rpc.accountInfo(address).isPresent()) {
                return;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new IllegalStateException("XRPL faucet funding did not validate: " + address);
    }

    private static JsonNode waitValidated(XrpRpcClient rpc, String txHash) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                JsonNode transaction = rpc.transaction(txHash);
                if (transaction.path("validated").asBoolean(false)) {
                    assertEquals("tesSUCCESS", transaction.path("meta").path("TransactionResult").asText());
                    return transaction;
                }
            } catch (RuntimeException error) {
                last = error;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new IllegalStateException("XRPL transaction did not validate: " + txHash, last);
    }

    private static void assertIssuedBalance(XrpRpcClient rpc, String address, String issuer,
                                            String currency, BigDecimal expected) {
        BigDecimal balance = BigDecimal.ZERO;
        for (JsonNode line : rpc.accountLines(address, issuer)) {
            if (currency.equalsIgnoreCase(line.path("currency").asText())) {
                balance = new BigDecimal(line.path("balance").asText("0"));
                break;
            }
        }
        assertAmount(expected, balance);
    }

    private static BigDecimal xrpBalance(XrpRpcClient rpc, String address) {
        return rpc.accountBalanceDrops(address).movePointLeft(6);
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance(CHAIN, symbol, accountId)
                .orElseThrow(() -> new IllegalStateException("missing ledger " + symbol + "/" + accountId));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected=" + expected + ", actual=" + actual);
    }

    private static void upsertMockToken(JdbcTemplate jdbc, String issuer, String symbol, String currency) {
        jdbc.update("""
                update token_config
                   set contract_address = ?, contract_address_base58 = ?, contract_address_hex = ?,
                       token_standard = 'ISSUED_CURRENCY', standard = 'XRPL_ISSUED', decimals = 6,
                       enabled = true, updated_at = now()
                 where chain = 'XRP' and network = 'testnet' and symbol = ?
                """, issuer + ":" + currency, issuer, symbol, symbol);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("xrp.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("xrp.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("xrp.db.password", "wallet123"));
        return dataSource;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for XRPL Testnet", error);
        }
    }
}
