package com.surprising.wallet.service.chain.xrp;

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
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrpTestnetFullFlowIntegrationTest {
    private static final String CHAIN = "XRP";
    private static final String RPC_URL = "https://s.altnet.rippletest.net:51234/";
    private static final String FAUCET_URL = "https://faucet.altnet.rippletest.net";
    private static final String USDC = "USDC";
    private static final String USDC_CURRENCY = "5553444300000000000000000000000000000000";
    private static final BcSignatureService SIGNATURES = new BcSignatureService();

    @Test
    void shouldExecuteNativeAndIssuedCurrencyFullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("xrp.live.flow.enabled"),
                "set -Dxrp.live.flow.enabled=true to broadcast XRPL Testnet transactions");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
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
        withdrawNative(repository, transactions, rpc, nativeWithdrawal, user, external,
                new BigDecimal("5"));
        LedgerBalanceRecord afterNativeWithdrawal = ledger(repository, CHAIN, user.getAccountId());
        assertAmount(faucetDeposit.subtract(new BigDecimal("5")), afterNativeWithdrawal.getTotalBalance());
        assertAmount(BigDecimal.ZERO, afterNativeWithdrawal.getLockedBalance());

        Seed issuerSeed = Seed.secp256k1Seed();
        KeyPair issuer = issuerSeed.deriveKeyPair();
        issuerSeed.destroy();
        String issuerAddress = issuer.publicKey().deriveAddress().value();
        fund(faucet, rpc, issuerAddress);
        waitValidated(rpc, enableDefaultRipple(rpc, issuer));
        upsertMockUsdc(jdbc, issuerAddress);
        TokenDefinition token = repository.findToken(CHAIN, USDC).orElseThrow();

        assertTrue(transactions.ensureIssuedCurrencyDepositReady(userUsdc, USDC).trustLineReady());
        assertTrue(transactions.ensureIssuedCurrencyDepositReady(externalUsdc, USDC).trustLineReady());
        String tokenDepositHash = sendIssued(rpc, issuer, user.getAddress(), USDC_CURRENCY,
                new BigDecimal("100"));
        waitValidated(rpc, tokenDepositHash);
        List<DepositEvent> tokenDeposits = scanner.scanAndCredit();
        assertTrue(tokenDeposits.stream().anyMatch(event -> tokenDepositHash.equals(event.txId())
                && USDC.equals(event.assetSymbol())));
        assertAmount(new BigDecimal("100"), ledger(repository, USDC, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, USDC, user.getAccountId()).getTotalBalance());

        String tokenWithdrawal = runId + "-USDC-WD";
        withdrawToken(repository, transactions, rpc, tokenWithdrawal, user, external, token,
                new BigDecimal("20"));
        LedgerBalanceRecord afterTokenWithdrawal = ledger(repository, USDC, user.getAccountId());
        assertAmount(new BigDecimal("80"), afterTokenWithdrawal.getTotalBalance());
        assertAmount(BigDecimal.ZERO, afterTokenWithdrawal.getLockedBalance());

        assertIssuedBalance(rpc, user.getAddress(), issuerAddress, USDC_CURRENCY, new BigDecimal("80"));
        assertIssuedBalance(rpc, external.getAddress(), issuerAddress, USDC_CURRENCY, new BigDecimal("20"));

        assertFalse(repository.freezeLedgerBalance(CHAIN, USDC, user.getAccountId(), new BigDecimal("1000")));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where available_balance < 0 "
                + "or locked_balance < 0 or total_balance < 0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order where chain='XRP' "
                + "and status <> 'CONFIRMED'", Integer.class));
        issuer.privateKey().destroy();
    }

    private static void withdrawNative(ChainJdbcRepository repository, XrpTransactionService transactions,
                                       XrpRpcClient rpc, String orderNo, ChainAddressRecord from,
                                       ChainAddressRecord to, BigDecimal amount) {
        createAndFreeze(repository, orderNo, from, to, CHAIN, amount);
        String txHash = transactions.sendNative(from, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), txHash));
        waitValidated(rpc, txHash);
        assertTrue(transactions.confirmWithdrawal(repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, CHAIN, from.getAccountId(), amount));
    }

    private static void withdrawToken(ChainJdbcRepository repository, XrpTransactionService transactions,
                                      XrpRpcClient rpc, String orderNo, ChainAddressRecord from,
                                      ChainAddressRecord to, TokenDefinition token, BigDecimal amount) {
        createAndFreeze(repository, orderNo, from, to, token.getSymbol(), amount);
        String txHash = transactions.sendIssuedCurrency(from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), txHash));
        waitValidated(rpc, txHash);
        assertTrue(transactions.confirmWithdrawal(repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, token.getSymbol(), from.getAccountId(), amount));
    }

    private static void createAndFreeze(ChainJdbcRepository repository, String orderNo,
                                        ChainAddressRecord from, ChainAddressRecord to,
                                        String symbol, BigDecimal amount) {
        assertEquals(1, repository.createWithdrawalOrder(orderNo, from.getUserId(), CHAIN, symbol,
                from.getAddress(), from.getAccountId(), to.getAddress(), amount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(CHAIN, symbol, from.getAccountId(), amount));
        assertEquals(1, repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN",
                from.getAddress(), null, null));
        assertEquals(1, repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()));
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

    private static void upsertMockUsdc(JdbcTemplate jdbc, String issuer) {
        jdbc.update("""
                update token_config
                   set contract_address = ?, contract_address_base58 = ?, contract_address_hex = 'USDC',
                       token_standard = 'ISSUED_CURRENCY', standard = 'XRPL_ISSUED', decimals = 6,
                       enabled = true, updated_at = now()
                 where chain = 'XRP' and network = 'testnet' and symbol = 'USDC'
                """, issuer + ":" + USDC_CURRENCY, issuer);
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
