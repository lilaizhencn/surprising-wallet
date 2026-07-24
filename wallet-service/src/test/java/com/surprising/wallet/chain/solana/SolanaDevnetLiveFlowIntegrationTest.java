package com.surprising.wallet.chain.solana;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolanaDevnetLiveFlowIntegrationTest {
    private static final long LAMPORTS_PER_SOL = 1_000_000_000L;

    @Test
    void liveSolAndMockSplDepositWithdrawAreIdempotent() {
        Assumptions.assumeTrue(Boolean.getBoolean("solana.live.enabled"),
                "set -Dsolana.live.enabled=true and SW_ED25519_SEED for live devnet validation");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("SOLANA_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("SOLANA_DB_USER", "wallet"),
                env("SOLANA_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = SolanaTenantIntegrationFixture.ensureTenant(jdbc);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        SolanaRpcClient rpc = new SolanaRpcClient(new ObjectMapper(),
                env("SOLANA_RPC_URL", "https://api.devnet.solana.com"));
        SolanaKeyService keys = new SolanaKeyService(masterSeed);
        SolanaAddressService addresses = new SolanaAddressService(keys, repository);
        SolanaTransactionService transactions = new SolanaTransactionService(rpc, keys, addresses, repository);
        SolanaDepositScanner scanner = new SolanaDepositScanner(rpc, repository);

        assertTrue(rpc.health());
        jdbc.update("""
                update chain_address
                   set enabled=false
                 where chain='SOLANA'
                   and not (asset_symbol='SOL' and user_id=0 and biz=0
                            and address_index=0 and wallet_role='DEPOSIT')
                """);
        long runBase = Long.getLong("solana.live.run-base",
                1_000_000L + Math.abs(System.currentTimeMillis() % 500_000L));
        long funderIndex = Long.getLong("solana.live.funder-index", runBase);
        long userAIndex = runBase + 1;
        long userBIndex = runBase + 2;
        long externalIndex = runBase + 4;
        Account funder = keys.account(funderIndex);
        Account external = keys.account(externalIndex);
        ChainAddressRecord userA = addresses.createNativeAddress(
                tenantId, 2001, 0, userAIndex, "DEPOSIT");
        ChainAddressRecord userB = addresses.createNativeAddress(
                tenantId, 2002, 0, userBIndex, "DEPOSIT");
        ChainAddressRecord hot = addresses.createNativeAddress(tenantId, 0, 0, 0L, "DEPOSIT");
        UUID custodyAddressId = SolanaTenantIntegrationFixture.attachDepositAddress(jdbc, userA);

        System.out.println("SOLANA_FUNDER=" + funder.getPublicKeyBase58());
        if (rpc.getBalance(funder.getPublicKeyBase58()) < 1_000_000_000L
                && !Boolean.getBoolean("solana.live.skip-airdrop")) {
            String airdrop = rpc.requestAirdrop(funder.getPublicKeyBase58(), 1_000_000_000L);
            transactions.requireSuccessfulConfirmation(airdrop, Duration.ofMinutes(2));
        }
        String depositA = sendNative(rpc, funder, userA.getAddress(), 20_000_000L);
        String depositB = sendNative(rpc, funder, userB.getAddress(), 20_000_000L);
        String gasFunding = sendNative(rpc, funder, hot.getAddress(), 50_000_000L);
        transactions.requireSuccessfulConfirmation(depositA, Duration.ofMinutes(2));
        transactions.requireSuccessfulConfirmation(depositB, Duration.ofMinutes(2));
        transactions.requireSuccessfulConfirmation(gasFunding, Duration.ofMinutes(2));

        scanner.scanAndCredit();
        BigDecimal userABefore = ledger(repository, "SOL", userA.getAccountId()).getTotalBalance();
        scanner.scanAndCredit();
        assertEquals(userABefore, ledger(repository, "SOL", userA.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("0.02"), userABefore);

        String withdrawOrder = "sol-live-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("0.005");
        String withdraw = transactions.withdrawNative(tenantId, withdrawOrder, userA.getUserId(), userA,
                external.getPublicKeyBase58(), withdrawAmount);
        assertEquals(withdraw, transactions.withdrawNative(
                tenantId, withdrawOrder, userA.getUserId(), userA,
                external.getPublicKeyBase58(), withdrawAmount));
        transactions.confirmWithdrawal(tenantId, withdrawOrder, "SOL", userA.getAccountId(),
                withdrawAmount.add(new BigDecimal("0.000005")));

        String collectionNo = "sol-live-collection-" + UUID.randomUUID();
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, "SOLANA", "SOL",
                userA.getAddress(), hot.getAddress(), new BigDecimal("0.004"),
                new BigDecimal("0.000005"), null));
        String collection = transactions.collectNative(
                tenantId, collectionNo, userA, hot.getAddress(), new BigDecimal("4000000"));
        assertEquals(collection, transactions.collectNative(
                tenantId, collectionNo, userA, hot.getAddress(), new BigDecimal("4000000")));
        assertTrue(transactions.confirmCollection(tenantId, collectionNo));

        String insufficientOrder = "sol-live-insufficient-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> transactions.withdrawNative(
                tenantId, insufficientOrder, userA.getUserId(), userA, external.getPublicKeyBase58(),
                new BigDecimal("999")));
        assertEquals("FAILED", repository.findWithdrawalOrder(tenantId, "SOLANA", insufficientOrder)
                .orElseThrow().getStatus());

        TokenFlow usdt = createAndFundMockToken("USDT", 6, runBase + 100, funderIndex,
                tenantId, custodyAddressId, funder, userA, userB, hot, external,
                rpc, keys, addresses, transactions, scanner, repository, jdbc);
        TokenFlow usdc = createAndFundMockToken("USDC", 6, runBase + 200, funderIndex,
                tenantId, custodyAddressId, funder, userA, userB, hot, external,
                rpc, keys, addresses, transactions, scanner, repository, jdbc);

        scanner.scanAndCredit();
        assertAmountEquals(new BigDecimal("0.014995"),
                ledger(repository, "SOL", userA.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("0.02"),
                ledger(repository, "SOL", userB.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("8"),
                ledger(repository, "USDT", userA.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("5"),
                ledger(repository, "USDT", userB.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("8"),
                ledger(repository, "USDC", userA.getAccountId()).getTotalBalance());
        assertAmountEquals(new BigDecimal("5"),
                ledger(repository, "USDC", userB.getAccountId()).getTotalBalance());
        BigDecimal customerSolLiability = ledger(repository, "SOL", userA.getAccountId()).getTotalBalance()
                .add(ledger(repository, "SOL", userB.getAccountId()).getTotalBalance());
        long controlledLamports = rpc.getBalance(userA.getAddress())
                + rpc.getBalance(userB.getAddress())
                + rpc.getBalance(hot.getAddress());
        assertTrue(BigDecimal.valueOf(controlledLamports).movePointLeft(9)
                .compareTo(customerSolLiability) >= 0);

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='SOLANA'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));
        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from deposit_record
                where chain='SOLANA' and tx_hash=? and credited=true
                """, Long.class, depositA));

        System.out.println("SOLANA_USER_A=" + userA.getAddress());
        System.out.println("SOLANA_USER_B=" + userB.getAddress());
        System.out.println("SOLANA_HOT=" + hot.getAddress());
        System.out.println("SOLANA_DEPOSIT_A_TX=" + depositA);
        System.out.println("SOLANA_DEPOSIT_B_TX=" + depositB);
        System.out.println("SOLANA_WITHDRAW_TX=" + withdraw);
        System.out.println("SOLANA_COLLECTION_TX=" + collection);
        System.out.println("SOLANA_USDT_MINT=" + usdt.mint());
        System.out.println("SOLANA_USDT_DEPOSIT_TX=" + usdt.deposit());
        System.out.println("SOLANA_USDT_WITHDRAW_TX=" + usdt.withdraw());
        System.out.println("SOLANA_USDT_COLLECTION_TX=" + usdt.collection());
        System.out.println("SOLANA_USDC_MINT=" + usdc.mint());
        System.out.println("SOLANA_USDC_DEPOSIT_TX=" + usdc.deposit());
        System.out.println("SOLANA_USDC_WITHDRAW_TX=" + usdc.withdraw());
        System.out.println("SOLANA_USDC_COLLECTION_TX=" + usdc.collection());
    }

    private TokenFlow createAndFundMockToken(
            String symbol, int decimals, long mintIndex, long funderIndex,
            UUID tenantId, UUID custodyAddressId, Account funder,
            ChainAddressRecord nativeUserA, ChainAddressRecord nativeUserB, ChainAddressRecord hot,
            Account external, SolanaRpcClient rpc, SolanaKeyService keys, SolanaAddressService addresses,
            SolanaTransactionService transactions, SolanaDepositScanner scanner,
            ChainJdbcRepository repository, JdbcTemplate jdbc) {
        Account mint = keys.account(mintIndex);
        long rent = rpc.call("getMinimumBalanceForRentExemption", List.of(82)).asLong();
        Transaction createMint = new Transaction()
                .addInstruction(SystemProgram.createAccount(
                        funder.getPublicKey(), mint.getPublicKey(), rent, 82, TokenProgram.PROGRAM_ID))
                .addInstruction(TokenProgram.initializeMint(
                        mint.getPublicKey(), decimals, funder.getPublicKey(), funder.getPublicKey()));
        String createMintTx = signAndSend(rpc, createMint, List.of(funder, mint));
        transactions.requireSuccessfulConfirmation(createMintTx, Duration.ofMinutes(2));

        String funderAta = addresses.associatedTokenAddress(funder.getPublicKeyBase58(), mint.getPublicKeyBase58());
        Transaction mintTokens = new Transaction()
                .addInstruction(AssociatedTokenProgram.createIdempotent(
                        funder.getPublicKey(), funder.getPublicKey(), mint.getPublicKey()))
                .addInstruction(TokenProgram.mintTo(
                        mint.getPublicKey(), new org.p2p.solanaj.core.PublicKey(funderAta),
                        funder.getPublicKey(), 100_000_000L));
        String mintTx = signAndSend(rpc, mintTokens, List.of(funder));
        transactions.requireSuccessfulConfirmation(mintTx, Duration.ofMinutes(2));

        jdbc.update("""
                insert into token_config(
                    chain, network, symbol, standard, token_standard, contract_address,
                    contract_address_base58, decimals, enabled, min_deposit, min_withdraw,
                    min_deposit_amount, min_withdraw_amount, collect_enabled, collect_threshold,
                    gas_strategy, confirmation_required, created_at, updated_at
                )
                values ('SOLANA','devnet',?,'SPL','SPL',?,?,?,true,1,1,1,1,true,1,
                        'SOL_FEE_PAYER',1,now(),now())
                on conflict(chain,network,symbol) do update set
                    network=excluded.network,
                    standard=excluded.standard,
                    token_standard=excluded.token_standard,
                    contract_address=excluded.contract_address,
                    contract_address_base58=excluded.contract_address_base58,
                    decimals=excluded.decimals,
                    enabled=true,
                    updated_at=now()
                """, symbol, mint.getPublicKeyBase58(), mint.getPublicKeyBase58(), decimals);
        jdbc.update("""
                insert into chain_asset(chain,symbol,asset_kind,contract_address,decimals,native_asset,active,
                                        min_transfer,min_withdraw)
                values('SOLANA',?,'TOKEN',?, ?,false,true,1,1)
                on conflict(chain,symbol) do update set
                    contract_address=excluded.contract_address,
                    decimals=excluded.decimals,
                    active=true,
                    updated_at=now()
                """, symbol, mint.getPublicKeyBase58(), decimals);

        ChainAddressRecord userA = addresses.createTokenAddress(tenantId, symbol, mint.getPublicKeyBase58(),
                nativeUserA.getUserId(), 0, nativeUserA.getAddressIndex(), "DEPOSIT");
        ChainAddressRecord userB = addresses.createTokenAddress(tenantId, symbol, mint.getPublicKeyBase58(),
                nativeUserB.getUserId(), 0, nativeUserB.getAddressIndex(), "DEPOSIT");
        String depositA = transactions.sendToken(funderIndex, mint.getPublicKeyBase58(),
                nativeUserA.getAddress(), 10_000_000L, decimals);
        String depositB = transactions.sendToken(funderIndex, mint.getPublicKeyBase58(),
                nativeUserB.getAddress(), 5_000_000L, decimals);
        transactions.requireSuccessfulConfirmation(depositA, Duration.ofMinutes(2));
        transactions.requireSuccessfulConfirmation(depositB, Duration.ofMinutes(2));
        scanner.scanAndCredit();
        BigDecimal beforeReplay = ledger(repository, symbol, userA.getAccountId()).getTotalBalance();
        assertAmountEquals(new BigDecimal("10"), beforeReplay);
        scanner.scanAndCredit();
        assertAmountEquals(beforeReplay, ledger(repository, symbol, userA.getAccountId()).getTotalBalance());

        String orderNo = "sol-" + symbol.toLowerCase() + "-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("2");
        String withdraw = transactions.withdrawToken(tenantId, orderNo, nativeUserA.getUserId(), userA,
                mint.getPublicKeyBase58(), external.getPublicKeyBase58(), withdrawAmount);
        assertEquals(withdraw, transactions.withdrawToken(
                tenantId, orderNo, nativeUserA.getUserId(), userA,
                mint.getPublicKeyBase58(), external.getPublicKeyBase58(), withdrawAmount));
        transactions.confirmWithdrawal(tenantId, orderNo, symbol, userA.getAccountId(), withdrawAmount);

        String collectionNo = "sol-" + symbol.toLowerCase() + "-collection-" + UUID.randomUUID();
        BigDecimal collectionAmount = new BigDecimal("3");
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, "SOLANA", symbol,
                userA.getOwnerAddress(), hot.getAddress(), collectionAmount, BigDecimal.ZERO, null));
        String collection = transactions.collectToken(
                tenantId, collectionNo, userA, mint.getPublicKeyBase58(),
                hot.getAddress(), collectionAmount);
        assertEquals(collection, transactions.collectToken(
                tenantId, collectionNo, userA, mint.getPublicKeyBase58(),
                hot.getAddress(), collectionAmount));
        assertTrue(transactions.confirmCollection(tenantId, collectionNo));

        String userAAta = addresses.associatedTokenAddress(
                nativeUserA.getAddress(), mint.getPublicKeyBase58());
        String userBAta = addresses.associatedTokenAddress(
                nativeUserB.getAddress(), mint.getPublicKeyBase58());
        String externalAta = addresses.associatedTokenAddress(
                external.getPublicKeyBase58(), mint.getPublicKeyBase58());
        String hotAta = addresses.associatedTokenAddress(
                hot.getAddress(), mint.getPublicKeyBase58());
        assertEquals(5_000_000L, rpc.getTokenAccountBalance(userAAta));
        assertEquals(5_000_000L, rpc.getTokenAccountBalance(userBAta));
        assertEquals(2_000_000L, rpc.getTokenAccountBalance(externalAta));
        assertEquals(3_000_000L, rpc.getTokenAccountBalance(hotAta));
        return new TokenFlow(mint.getPublicKeyBase58(), depositA, withdraw, collection);
    }

    private static String sendNative(SolanaRpcClient rpc, Account from, String to, long lamports) {
        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.transfer(
                        from.getPublicKey(), new org.p2p.solanaj.core.PublicKey(to), lamports));
        return signAndSend(rpc, transaction, List.of(from));
    }

    private static String signAndSend(SolanaRpcClient rpc, Transaction transaction, List<Account> signers) {
        transaction.setRecentBlockHash(rpc.getLatestBlockhash());
        transaction.sign(signers);
        return rpc.sendTransaction(transaction.serialize());
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance("SOLANA", symbol, accountId).orElseThrow();
    }

    private static void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected amount " + expected.toPlainString()
                        + " but was " + actual.toPlainString());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record TokenFlow(String mint, String deposit, String withdraw, String collection) {
    }
}
