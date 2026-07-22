package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.smartcontract.token.ft.JettonMinter;
import org.ton.ton4j.smartcontract.token.ft.JettonWallet;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TonLiveMockJettonFlowIntegrationTest {
    private static final long OWNER_INDEX = 1_100_001L;
    private static final long EXTERNAL_INDEX = 1_100_002L;
    private static final long HOT_INDEX = 0L;
    private static final long NANO = 1_000_000_000L;
    private static final Duration FLOW_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void liveNativeAndMockJettonDepositWithdrawAreIdempotent() {
        Assumptions.assumeTrue(Boolean.getBoolean("ton.live.enabled"),
                "set -Dton.live.enabled=true and SW_ED25519_SEED for TON live validation");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("TON_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("TON_DB_USER", "wallet"),
                env("TON_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        TonCenterClient rpc = new TonCenterClient(new ObjectMapper(),
                env("TON_RPC_URL", "https://testnet.toncenter.com/api/v2"),
                env("TONCENTER_API_KEY", ""));
        TonKeyService keys = new TonKeyService(masterSeed);
        TonAddressService addresses = new TonAddressService(keys, repository);
        TonTransactionService transactions = new TonTransactionService(rpc, keys, repository);
        TonDepositScanner scanner = new TonDepositScanner(rpc, addresses, repository);

        ChainAddressRecord owner = addresses.createNativeAddress(4001, 0, OWNER_INDEX, "DEPOSIT");
        ChainAddressRecord external = addresses.createNativeAddress(4002, 0, EXTERNAL_INDEX, "EXTERNAL");
        ChainAddressRecord hot = addresses.createNativeAddress(0, 0, HOT_INDEX, "DEPOSIT");
        String externalFundingOwner = keys.wallet(OWNER_INDEX)
                .getAddress().toString(true, true, false, true);
        long startBalance = rpc.balance(owner.getAddress());
        Assumptions.assumeTrue(startBalance > NANO,
                "fund " + owner.getAddress() + " with at least 1 testnet TON");
        Assumptions.assumeTrue(rpc.balance(externalFundingOwner) > NANO,
                "fund and deploy external test wallet " + externalFundingOwner + " with at least 1 testnet TON");

        scanner.scanAndCredit();
        BigDecimal nativeBeforeReplay = ledger(repository, "TON", owner.getAccountId()).getTotalBalance();
        scanner.scanAndCredit();
        assertEquals(nativeBeforeReplay, ledger(repository, "TON", owner.getAccountId()).getTotalBalance());

        String walletDeployHash = deployWalletIfNeeded(owner, rpc, transactions);
        MockJetton usdt = deployAndMint("USDT", owner, externalFundingOwner,
                rpc, transactions, addresses, scanner, repository, jdbc);
        MockJetton usdc = deployAndMint("USDC", owner, externalFundingOwner,
                rpc, transactions, addresses, scanner, repository, jdbc);

        waitForJettonLedger(scanner, repository, "USDT", usdt.userJettonWallet().getAccountId(),
                new BigDecimal("1000"));
        waitForJettonLedger(scanner, repository, "USDC", usdc.userJettonWallet().getAccountId(),
                new BigDecimal("1000"));
        BigDecimal usdtBeforeReplay = ledger(repository, "USDT", usdt.userJettonWallet().getAccountId()).getTotalBalance();
        scanner.scanAndCredit();
        assertEquals(usdtBeforeReplay, ledger(repository, "USDT", usdt.userJettonWallet().getAccountId()).getTotalBalance());

        long nativeWithdrawSeqno = rpc.seqno(owner.getAddress());
        String nativeWithdrawOrder = "ton-live-withdraw-" + UUID.randomUUID();
        BigDecimal nativeWithdrawAmount = new BigDecimal("0.05");
        String nativeWithdraw = transactions.withdrawNative(nativeWithdrawOrder, owner.getUserId(), owner,
                external.getAddress(), nativeWithdrawAmount, "TON withdraw gate");
        assertEquals(nativeWithdraw, transactions.withdrawNative(nativeWithdrawOrder, owner.getUserId(), owner,
                external.getAddress(), nativeWithdrawAmount, "TON withdraw gate"));
        waitForSeqnoGreaterThan(rpc, owner.getAddress(), nativeWithdrawSeqno, FLOW_TIMEOUT);
        assertTrue(transactions.confirmWithdrawal(nativeWithdrawOrder, "TON", owner.getAccountId(),
                new BigDecimal("0.055")));

        TokenTx usdtTx = withdrawAndCollectJetton("USDT", usdt, external, hot, rpc, transactions, repository);
        TokenTx usdcTx = withdrawAndCollectJetton("USDC", usdc, external, hot, rpc, transactions, repository);

        assertEquals(0L, jdbc.queryForObject("""
                select count(*) from ledger_balance
                where chain='TON'
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, Long.class));

        System.out.println("TON_OWNER=" + owner.getAddress());
        System.out.println("TON_EXTERNAL=" + external.getAddress());
        System.out.println("TON_HOT=" + hot.getAddress());
        System.out.println("TON_WALLET_DEPLOY_TX=" + walletDeployHash);
        System.out.println("TON_NATIVE_WITHDRAW_TX=" + nativeWithdraw);
        System.out.println("TON_USDT_MASTER=" + usdt.masterAddress());
        System.out.println("TON_USDT_USER_JETTON_WALLET=" + usdt.userJettonWallet().getAddress());
        System.out.println("TON_USDT_MASTER_DEPLOY_TX=" + usdt.deployHash());
        System.out.println("TON_USDT_MINT_TX=" + usdt.mintHash());
        System.out.println("TON_USDT_WITHDRAW_TX=" + usdtTx.withdrawHash());
        System.out.println("TON_USDC_MASTER=" + usdc.masterAddress());
        System.out.println("TON_USDC_USER_JETTON_WALLET=" + usdc.userJettonWallet().getAddress());
        System.out.println("TON_USDC_MASTER_DEPLOY_TX=" + usdc.deployHash());
        System.out.println("TON_USDC_MINT_TX=" + usdc.mintHash());
        System.out.println("TON_USDC_WITHDRAW_TX=" + usdcTx.withdrawHash());
    }

    private static String deployWalletIfNeeded(ChainAddressRecord owner,
                                               TonCenterClient rpc, TonTransactionService transactions) {
        if ("active".equalsIgnoreCase(rpc.addressInformation(owner.getAddress()).path("state").asText())) {
            return "already-active";
        }
        TonTransactionService.PreparedTransfer deploy = transactions.prepareWalletDeploy(owner);
        String hash = transactions.broadcast(deploy);
        waitForState(rpc, owner.getAddress(), "active", FLOW_TIMEOUT);
        return hash;
    }

    private static MockJetton deployAndMint(String symbol, ChainAddressRecord owner,
                                            String externalFundingOwner,
                                            TonCenterClient rpc, TonTransactionService transactions,
                                            TonAddressService addresses, TonDepositScanner scanner,
                                            ChainJdbcRepository repository, JdbcTemplate jdbc) {
        String uri = "https://example.invalid/surprising-wallet/ton-testnet-" + symbol.toLowerCase() + ".json";
        JettonMinter minter = JettonMinter.builder()
                .adminAddress(Address.of(owner.getAddress()))
                .content(NftUtils.createOffChainUriCell(uri))
                .wc(0)
                .build();
        String masterAddress = minter.getAddress().toString(true, true, true, true);
        configureToken(symbol, masterAddress, jdbc);
        ChainAddressRecord userJettonWallet = addresses.registerJettonWallet(symbol,
                jettonWalletAddress(owner.getAddress(), masterAddress), owner.getUserId(), owner.getBiz(),
                owner.getAddressIndex(), "DEPOSIT");

        String deployHash = "already-active";
        if (!"active".equalsIgnoreCase(rpc.addressInformation(masterAddress).path("state").asText())) {
            long deploySeqno = rpc.seqno(owner.getAddress());
            TonTransactionService.PreparedTransfer deploy = transactions.prepareContractCall(
                    owner, masterAddress, BigInteger.valueOf(500_000_000L),
                    minter.getStateInit(), null, false);
            deployHash = transactions.broadcast(deploy);
            waitForSeqnoGreaterThan(rpc, owner.getAddress(), deploySeqno, FLOW_TIMEOUT);
            waitForState(rpc, masterAddress, "active", FLOW_TIMEOUT);
        }

        scanner.scanAndCredit();
        if (repository.findLedgerBalance("TON", symbol, userJettonWallet.getAccountId())
                .map(LedgerBalanceRecord::getTotalBalance)
                .filter(balance -> balance.compareTo(new BigDecimal("1000")) >= 0)
                .isPresent()) {
            return new MockJetton(symbol, masterAddress, userJettonWallet, deployHash, "existing-balance");
        }

        long mintSeqno = rpc.seqno(owner.getAddress());
        Cell mintBody = JettonMinter.createMintBody(
                System.currentTimeMillis(), Address.of(externalFundingOwner), BigInteger.valueOf(80_000_000L),
                BigInteger.valueOf(1_000_000_000L), null,
                null, BigInteger.ZERO, null);
        TonTransactionService.PreparedTransfer mint = transactions.prepareContractCall(
                owner, masterAddress, BigInteger.valueOf(220_000_000L),
                null, mintBody, true);
        String mintHash = transactions.broadcast(mint);
        waitForSeqnoGreaterThan(rpc, owner.getAddress(), mintSeqno, FLOW_TIMEOUT);
        String externalJettonWallet = jettonWalletAddress(externalFundingOwner, masterAddress);
        waitForState(rpc, externalJettonWallet, "active", FLOW_TIMEOUT);

        long depositSeqno = rpc.seqno(externalFundingOwner);
        TonTransactionService.PreparedTransfer deposit = transactions.prepareJetton(
                OWNER_INDEX, externalJettonWallet, owner.getAddress(), BigInteger.valueOf(1_000_000_000L),
                externalFundingOwner, symbol + " external deposit");
        transactions.broadcast(deposit);
        waitForSeqnoGreaterThan(rpc, externalFundingOwner, depositSeqno, FLOW_TIMEOUT);
        waitForState(rpc, userJettonWallet.getAddress(), "active", FLOW_TIMEOUT);
        return new MockJetton(symbol, masterAddress, userJettonWallet, deployHash, mintHash);
    }

    private static TokenTx withdrawAndCollectJetton(String symbol, MockJetton jetton,
                                                    ChainAddressRecord external, ChainAddressRecord hot,
                                                    TonCenterClient rpc, TonTransactionService transactions,
                                                    ChainJdbcRepository repository) {
        long withdrawSeqno = rpc.seqno(jetton.userJettonWallet().getOwnerAddress());
        String orderNo = "ton-" + symbol.toLowerCase() + "-withdraw-" + UUID.randomUUID();
        BigDecimal withdrawAmount = new BigDecimal("100");
        String withdraw = transactions.withdrawJetton(orderNo, jetton.userJettonWallet().getUserId(),
                jetton.userJettonWallet(), jetton.masterAddress(), external.getAddress(),
                withdrawAmount, symbol + " withdraw gate");
        assertEquals(withdraw, transactions.withdrawJetton(orderNo, jetton.userJettonWallet().getUserId(),
                jetton.userJettonWallet(), jetton.masterAddress(), external.getAddress(),
                withdrawAmount, symbol + " withdraw gate"));
        waitForSeqnoGreaterThan(rpc, jetton.userJettonWallet().getOwnerAddress(), withdrawSeqno,
                FLOW_TIMEOUT);
        assertTrue(transactions.confirmWithdrawal(orderNo, symbol, jetton.userJettonWallet().getAccountId(),
                withdrawAmount));

        assertTrue(repository.findLedgerBalance("TON", symbol, jetton.userJettonWallet().getAccountId())
                .orElseThrow().getLockedBalance().signum() == 0);
        return new TokenTx(withdraw);
    }

    private static void waitForJettonLedger(TonDepositScanner scanner, ChainJdbcRepository repository, String symbol,
                                            String accountId, BigDecimal minimum) {
        Instant deadline = Instant.now().plus(FLOW_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            scanner.scanAndCredit();
            if (repository.findLedgerBalance("TON", symbol, accountId)
                    .map(LedgerBalanceRecord::getTotalBalance)
                    .filter(balance -> balance.compareTo(minimum) >= 0)
                    .isPresent()) {
                return;
            }
            sleep(2_000L);
        }
        throw new IllegalStateException("TON " + symbol + " ledger was not credited");
    }

    private static void waitForSeqnoGreaterThan(TonCenterClient rpc, String address,
                                                long previousSeqno, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (rpc.seqno(address) > previousSeqno) {
                return;
            }
            sleep(2_000L);
        }
        throw new IllegalStateException("TON seqno did not advance for " + address);
    }

    private static void waitForState(TonCenterClient rpc, String address, String expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode state = rpc.addressInformation(address);
            if (expected.equalsIgnoreCase(state.path("state").asText())) {
                return;
            }
            sleep(2_000L);
        }
        throw new IllegalStateException("TON account did not become " + expected + ": " + address);
    }

    private static String jettonWalletAddress(String ownerAddress, String masterAddress) {
        return JettonWallet.calculateUserJettonWalletAddress(
                0, Address.of(ownerAddress), Address.of(masterAddress), JettonWallet.CODE_CELL)
                .toString(true, true, true, true);
    }

    private static void configureToken(String symbol, String masterAddress, JdbcTemplate jdbc) {
        jdbc.update("""
                insert into token_config(
                    chain, network, symbol, standard, token_standard, contract_address,
                    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
                    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
                    confirmation_required, created_at, updated_at
                )
                values ('TON','testnet',?,'JETTON','JETTON',?,6,true,1,1,1,1,true,1,
                        'TON_FORWARD_FEE',1,now(),now())
                on conflict(chain,network,symbol) do update set
                    network=excluded.network,
                    standard=excluded.standard,
                    token_standard=excluded.token_standard,
                    contract_address=excluded.contract_address,
                    decimals=excluded.decimals,
                    enabled=true,
                    updated_at=now()
                """, symbol, masterAddress);
        jdbc.update("""
                insert into chain_asset(chain,symbol,asset_kind,contract_address,decimals,native_asset,active,
                                        min_transfer,min_withdraw)
                values('TON',?,'JETTON',?,6,false,true,1,1)
                on conflict(chain,symbol) do update set
                    asset_kind=excluded.asset_kind,
                    contract_address=excluded.contract_address,
                    decimals=excluded.decimals,
                    native_asset=false,
                    active=true,
                    updated_at=now()
                """, symbol, masterAddress);
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance("TON", symbol, accountId).orElseThrow();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TON live wait interrupted", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record MockJetton(String symbol, String masterAddress, ChainAddressRecord userJettonWallet,
                              String deployHash, String mintHash) {
    }

    private record TokenTx(String withdrawHash) {
    }
}
