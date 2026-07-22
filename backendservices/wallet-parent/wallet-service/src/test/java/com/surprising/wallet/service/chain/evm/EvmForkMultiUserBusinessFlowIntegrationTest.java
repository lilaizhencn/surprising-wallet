package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.EvmNonceRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-user EVM business-flow validation on a real local fork.
 * The test exercises deposit scanning, withdrawal freezing, retry recovery,
 * collection recovery, nonce reservation and ledger-vs-chain reconciliation.
 */
class EvmForkMultiUserBusinessFlowIntegrationTest {
    private static final UUID TEST_TENANT_ID = UUID.fromString("77020000-0000-0000-0000-000000000001");
    private static final String LOCAL_RPC = "http://127.0.0.1:8545";
    private static final BigDecimal WEI_PER_NATIVE = new BigDecimal("1000000000000000000");
    private static final BigDecimal TOKEN_DECIMAL = new BigDecimal("1000000");
    private static final BigDecimal GAS_BUFFER = new BigDecimal("0.01");

    @Test
    void shouldValidateMultiUserBusinessFlowAndRecoveryOnFork() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.multiuser.enabled"),
                "set -Devm.multiuser.enabled=true and start local fork on 127.0.0.1:8545");

        ChainType chain = ChainType.valueOf(System.getProperty("evm.fork.chain", "ETH"));
        String nativeSymbol = System.getProperty("evm.native.symbol", "ETH");
        int confirmations = Integer.getInteger("evm.confirmations", 1);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbcTemplate);
        EvmDepositScanner scanner = new EvmDepositScanner(repository, new EvmLogScanner(), LOCAL_RPC, confirmations);
        Web3j web3j = Web3j.build(new HttpService(LOCAL_RPC));

        try {
            List<String> accounts = web3j.ethAccounts().send().getAccounts();
            assertTrue(accounts.size() >= 4, "Hardhat fork must expose external recipient accounts");
            String deployer = accounts.getFirst();
            String externalNativeRecipient = accounts.get(1);
            String externalTokenRecipient = accounts.get(2);
            long chainId = web3j.ethChainId().send().getChainId().longValueExact();
            assertEquals(Long.getLong("evm.expected.chainId", chainId), chainId, "fork chainId must match target chain");

            Actors actors = actors(chain);
            prepareDatabase(jdbcTemplate, chain, nativeSymbol, actors);
            List<TokenContract> tokens = tokenContracts(jdbcTemplate, chain);
            TokenContract collectionToken = tokens.isEmpty() ? null : tokens.getFirst();
            TokenContract withdrawalToken = tokens.size() > 1 ? tokens.get(1) : collectionToken;

            // Setup confirmed balances for users that will withdraw or pay collection gas.
            scanNativeDeposit(scanner, chain, nativeSymbol, confirmations,
                    sendUnlockedNative(web3j, deployer, actors.userB().address(), new BigDecimal("0.20")));
            scanNativeDeposit(scanner, chain, nativeSymbol, confirmations,
                    sendUnlockedNative(web3j, deployer, actors.userC().address(), new BigDecimal("1.00")));
            scanNativeDeposit(scanner, chain, nativeSymbol, confirmations,
                    sendUnlockedNative(web3j, deployer, actors.userD().address(), new BigDecimal("0.20")));
            if (withdrawalToken != null) {
                scanTokenDeposit(scanner, chain, confirmations,
                        sendUnlockedTokenCall(web3j, deployer, withdrawalToken.address(),
                                encodeTransfer(actors.userD().address(), new BigDecimal("80"))));
            }

            // Scanner is "stopped": chain deposits are mined, but DB has not been scanned yet.
            TransactionReceipt userAEthDeposit = sendUnlockedNative(web3j, deployer, actors.userA().address(), new BigDecimal("1.25"));
            TransactionReceipt userBTokenDeposit = collectionToken == null ? null
                    : sendUnlockedTokenCall(web3j, deployer, collectionToken.address(),
                    encodeTransfer(actors.userB().address(), new BigDecimal("100")));
            assertBalanceEquals(BigDecimal.ZERO, ledgerOrZero(jdbcTemplate, chain, nativeSymbol, actors.userA().address()));
            if (collectionToken != null) {
                assertBalanceEquals(BigDecimal.ZERO,
                        ledgerOrZero(jdbcTemplate, chain, collectionToken.symbol(), actors.userB().address()));
            }

            // Scanner recovery: rescan the mined blocks and assert idempotent crediting.
            scanNativeDeposit(scanner, chain, nativeSymbol, confirmations, userAEthDeposit);
            if (userBTokenDeposit != null) {
                scanTokenDeposit(scanner, chain, confirmations, userBTokenDeposit);
            }
            scanNativeDeposit(scanner, chain, nativeSymbol, confirmations, userAEthDeposit);
            if (userBTokenDeposit != null) {
                scanTokenDeposit(scanner, chain, confirmations, userBTokenDeposit);
            }
            assertBalanceEquals(new BigDecimal("1.25"), ledgerOrZero(jdbcTemplate, chain, nativeSymbol, actors.userA().address()));
            assertDepositCount(jdbcTemplate, chain, nativeSymbol, actors.userA().address(), 1);
            if (collectionToken != null) {
                assertBalanceEquals(new BigDecimal("100"),
                        ledgerOrZero(jdbcTemplate, chain, collectionToken.symbol(), actors.userB().address()));
                assertDepositCount(jdbcTemplate, chain, collectionToken.symbol(), actors.userB().address(), 1);
            }

            executeFailedThenRecoveredNativeWithdrawal(jdbcTemplate, repository, web3j, chain, nativeSymbol,
                    actors.userC(), externalNativeRecipient, new BigDecimal("0.05"), chainId);
            if (withdrawalToken != null) {
                executeTokenWithdrawal(jdbcTemplate, repository, web3j, chain, nativeSymbol, actors.userD(),
                        withdrawalToken.address(), withdrawalToken.symbol(), externalTokenRecipient,
                        new BigDecimal("12.5"), chainId);
            }
            executeNativeCollection(jdbcTemplate, repository, web3j, chain, nativeSymbol, actors.userA(),
                    actors.hotWallet(), new BigDecimal("0.30"), chainId);
            if (collectionToken != null) {
                executeInterruptedTokenCollectionRecovery(jdbcTemplate, repository, web3j, chain, nativeSymbol,
                        actors.userB(), actors.hotWallet(), collectionToken.address(), collectionToken.symbol(),
                        new BigDecimal("100"), chainId);
                assertFalse(repository.freezeLedgerBalance(
                                chain.name(), collectionToken.symbol(), actors.userB().address(), BigDecimal.ONE),
                        "double-spend guard must reject a second token collection after balance reached zero");
            }
            assertAddressMatchesChain(jdbcTemplate, web3j, chain, nativeSymbol, tokens, actors.userA());
            assertAddressMatchesChain(jdbcTemplate, web3j, chain, nativeSymbol, tokens, actors.userB());
            assertAddressMatchesChain(jdbcTemplate, web3j, chain, nativeSymbol, tokens, actors.userC());
            assertAddressMatchesChain(jdbcTemplate, web3j, chain, nativeSymbol, tokens, actors.userD());
            assertAddressMatchesChain(jdbcTemplate, web3j, chain, nativeSymbol, tokens, actors.hotWallet());
            assertEquals(BigInteger.ONE, pendingNonce(web3j, actors.userA().credentials()), "user A native collection nonce");
            assertEquals(collectionToken == null ? BigInteger.ZERO : BigInteger.ONE,
                    pendingNonce(web3j, actors.userB().credentials()), "user B token collection nonce");
            assertEquals(BigInteger.ONE, pendingNonce(web3j, actors.userC().credentials()), "user C recovered withdraw nonce");
            assertEquals(withdrawalToken == null ? BigInteger.ZERO : BigInteger.ONE,
                    pendingNonce(web3j, actors.userD().credentials()), "user D token withdrawal nonce");
            assertEquals(0, countNonConfirmedOrders(jdbcTemplate, chain), "all stability orders must be terminal CONFIRMED");
        } finally {
            web3j.shutdown();
        }
    }

    private static void executeFailedThenRecoveredNativeWithdrawal(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                                                   Web3j web3j, ChainType chain, String nativeSymbol,
                                                                   Actor user, String recipient, BigDecimal amount,
                                                                   long chainId) throws Exception {
        String orderNo = orderNo(chain, user.label(), nativeSymbol, "WD-RECOVERY");
        BigDecimal frozen = amount.add(GAS_BUFFER);
        createOrder(jdbcTemplate, orderNo, user.userId(), chain, nativeSymbol, user.address(), recipient, amount);
        assertTrue(repository.freezeLedgerBalance(chain.name(), nativeSymbol, user.address(), frozen));
        updateOrder(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO, null);
        BigInteger failedNonce = reserveNonce(repository, web3j, chain, user);
        updateOrder(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO, null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> sendSignedNative(web3j, user.credentials(), recipient, amount, chainId, failedNonce, BigInteger.ONE));
        updateOrder(jdbcTemplate, orderNo, "FAILED", null, BigDecimal.ZERO, error.getMessage());
        assertTrue(repository.releaseLockedBalance(chain.name(), nativeSymbol, user.address(), frozen));
        assertBalanceEquals(BigDecimal.ZERO, locked(jdbcTemplate, chain, nativeSymbol, user.address()));

        updateOrder(jdbcTemplate, orderNo, "RETRYING", null, BigDecimal.ZERO, "retry after rejected raw tx");
        assertTrue(repository.freezeLedgerBalance(chain.name(), nativeSymbol, user.address(), frozen));
        BigInteger retryNonce = reserveNonce(repository, web3j, chain, user);
        assertEquals(failedNonce, retryNonce, "rejected transaction must not consume nonce");
        SignedTx tx = sendSignedNative(web3j, user.credentials(), recipient, amount, chainId, retryNonce, BigInteger.valueOf(21_000L));
        updateOrder(jdbcTemplate, orderNo, "SENT", tx.receipt().getTransactionHash(), fee(tx), null);
        settleFrozen(repository, chain.name(), nativeSymbol, user.address(), frozen, amount.add(fee(tx)));
        updateOrder(jdbcTemplate, orderNo, "CONFIRMED", tx.receipt().getTransactionHash(), fee(tx), null);
        recordEvmTx(repository, chain, nativeSymbol, null, user.address(), recipient, amount, tx);
    }

    private static void executeTokenWithdrawal(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository, Web3j web3j,
                                               ChainType chain, String nativeSymbol, Actor user, String contract,
                                               String tokenSymbol, String recipient, BigDecimal amount,
                                               long chainId) throws Exception {
        String orderNo = orderNo(chain, user.label(), tokenSymbol, "WD");
        createOrder(jdbcTemplate, orderNo, user.userId(), chain, tokenSymbol, user.address(), recipient, amount);
        assertTrue(repository.freezeLedgerBalance(chain.name(), tokenSymbol, user.address(), amount));
        assertTrue(repository.freezeLedgerBalance(chain.name(), nativeSymbol, user.address(), GAS_BUFFER));
        updateOrder(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO, null);
        BigInteger nonce = reserveNonce(repository, web3j, chain, user);
        assertTrue(estimateTokenGas(web3j, user.address(), contract, recipient, amount).compareTo(BigInteger.ZERO) > 0);
        updateOrder(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO, null);
        SignedTx tx = sendSignedTokenTransfer(web3j, user.credentials(), contract, recipient, amount, chainId, nonce);
        updateOrder(jdbcTemplate, orderNo, "SENT", tx.receipt().getTransactionHash(), fee(tx), null);
        assertTrue(repository.settleLockedDebit(chain.name(), tokenSymbol, user.address(), amount));
        settleFrozen(repository, chain.name(), nativeSymbol, user.address(), GAS_BUFFER, fee(tx));
        updateOrder(jdbcTemplate, orderNo, "CONFIRMED", tx.receipt().getTransactionHash(), fee(tx), null);
        recordEvmTx(repository, chain, tokenSymbol, contract, user.address(), recipient, amount, tx);
    }

    private static void executeNativeCollection(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository, Web3j web3j,
                                                ChainType chain, String nativeSymbol, Actor user, Actor hotWallet,
                                                BigDecimal amount, long chainId) throws Exception {
        String orderNo = orderNo(chain, user.label(), nativeSymbol, "COLL");
        BigDecimal frozen = amount.add(GAS_BUFFER);
        createOrder(jdbcTemplate, orderNo, user.userId(), chain, nativeSymbol, user.address(), hotWallet.address(), amount);
        assertTrue(repository.freezeLedgerBalance(chain.name(), nativeSymbol, user.address(), frozen));
        updateOrder(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO, null);
        BigInteger nonce = reserveNonce(repository, web3j, chain, user);
        assertTrue(estimateNativeGas(web3j, user.address(), hotWallet.address(), amount).compareTo(BigInteger.ZERO) > 0);
        updateOrder(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO, null);
        SignedTx tx = sendSignedNative(web3j, user.credentials(), hotWallet.address(), amount, chainId, nonce, BigInteger.valueOf(21_000L));
        updateOrder(jdbcTemplate, orderNo, "SENT", tx.receipt().getTransactionHash(), fee(tx), null);
        settleFrozen(repository, chain.name(), nativeSymbol, user.address(), frozen, amount.add(fee(tx)));
        repository.incrementLedgerBalance(TEST_TENANT_ID, chain.name(), nativeSymbol, hotWallet.address(), amount);
        updateOrder(jdbcTemplate, orderNo, "CONFIRMED", tx.receipt().getTransactionHash(), fee(tx), null);
        recordEvmTx(repository, chain, nativeSymbol, null, user.address(), hotWallet.address(), amount, tx);
    }

    private static void executeInterruptedTokenCollectionRecovery(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                                                  Web3j web3j, ChainType chain, String nativeSymbol,
                                                                  Actor user, Actor hotWallet, String contract,
                                                                  String tokenSymbol, BigDecimal amount,
                                                                  long chainId) throws Exception {
        String orderNo = orderNo(chain, user.label(), tokenSymbol, "COLL-RECOVERY");
        createOrder(jdbcTemplate, orderNo, user.userId(), chain, tokenSymbol, user.address(), hotWallet.address(), amount);
        assertTrue(repository.freezeLedgerBalance(chain.name(), tokenSymbol, user.address(), amount));
        assertTrue(repository.freezeLedgerBalance(chain.name(), nativeSymbol, user.address(), GAS_BUFFER));
        updateOrder(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO, "simulated interruption before broadcast");

        SignedTx tx = recoverTokenCollection(jdbcTemplate, repository, web3j, chain, nativeSymbol, user, hotWallet,
                contract, tokenSymbol, amount, chainId, orderNo);
        BigDecimal hotBalanceAfterRecovery = tokenBalance(web3j, contract, hotWallet.address());
        recoverTokenCollection(jdbcTemplate, repository, web3j, chain, nativeSymbol, user, hotWallet,
                contract, tokenSymbol, amount, chainId, orderNo);
        assertBalanceEquals(hotBalanceAfterRecovery, tokenBalance(web3j, contract, hotWallet.address()));
        recordEvmTx(repository, chain, tokenSymbol, contract, user.address(), hotWallet.address(), amount, tx);
    }

    private static SignedTx recoverTokenCollection(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository, Web3j web3j,
                                                   ChainType chain, String nativeSymbol, Actor user, Actor hotWallet,
                                                   String contract, String tokenSymbol, BigDecimal amount,
                                                   long chainId, String orderNo) throws Exception {
        String status = jdbcTemplate.queryForObject("select status from withdrawal_order where order_no = ?",
                String.class, orderNo);
        if ("CONFIRMED".equals(status)) {
            return null;
        }
        updateOrder(jdbcTemplate, orderNo, "RETRYING", null, BigDecimal.ZERO, "recover interrupted collection");
        BigInteger nonce = reserveNonce(repository, web3j, chain, user);
        assertTrue(estimateTokenGas(web3j, user.address(), contract, hotWallet.address(), amount).compareTo(BigInteger.ZERO) > 0);
        updateOrder(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO, null);
        SignedTx tx = sendSignedTokenTransfer(web3j, user.credentials(), contract, hotWallet.address(), amount, chainId, nonce);
        updateOrder(jdbcTemplate, orderNo, "SENT", tx.receipt().getTransactionHash(), fee(tx), null);
        assertTrue(repository.settleLockedDebit(chain.name(), tokenSymbol, user.address(), amount));
        settleFrozen(repository, chain.name(), nativeSymbol, user.address(), GAS_BUFFER, fee(tx));
        repository.incrementLedgerBalance(TEST_TENANT_ID, chain.name(), tokenSymbol, hotWallet.address(), amount);
        updateOrder(jdbcTemplate, orderNo, "CONFIRMED", tx.receipt().getTransactionHash(), fee(tx), null);
        return tx;
    }

    private static void settleFrozen(ChainJdbcRepository repository, String chain, String asset, String account,
                                     BigDecimal frozen, BigDecimal actualDebit) {
        BigDecimal lockedDebit = actualDebit.min(frozen);
        if (lockedDebit.signum() > 0) {
            assertTrue(repository.settleLockedDebit(chain, asset, account, lockedDebit));
        }
        BigDecimal remainder = frozen.subtract(lockedDebit);
        if (remainder.signum() > 0) {
            assertTrue(repository.releaseLockedBalance(chain, asset, account, remainder));
        }
        BigDecimal unfrozenDebit = actualDebit.subtract(lockedDebit);
        if (unfrozenDebit.signum() > 0) {
            assertTrue(repository.debitLedgerBalance(chain, asset, account, unfrozenDebit));
        }
    }

    private static void prepareDatabase(JdbcTemplate jdbcTemplate, ChainType chain, String nativeSymbol, Actors actors) {
        jdbcTemplate.update("""
                insert into custody_tenant(id, slug, name)
                values (?, 'evm-fork-integration', 'EVM fork integration tenant')
                on conflict (id) do nothing
                """, TEST_TENANT_ID);
        List<Actor> all = List.of(actors.userA(), actors.userB(), actors.userC(), actors.userD(), actors.hotWallet());
        jdbcTemplate.update("delete from withdrawal_order where chain = ? and order_no like ?",
                chain.name(), "EVM-STABILITY-" + chain.name() + "-%");
        for (Actor actor : all) {
            String account = actor.address().toLowerCase(Locale.ROOT);
            jdbcTemplate.update("delete from deposit_record where chain = ? and (lower(to_address) = lower(?) or lower(from_address) = lower(?))",
                    chain.name(), account, account);
            jdbcTemplate.update("delete from evm_tx where chain = ? and (lower(to_address) = lower(?) or lower(from_address) = lower(?))",
                    chain.name(), account, account);
            jdbcTemplate.update("delete from evm_token_transfer where chain = ? and (lower(to_address) = lower(?) or lower(from_address) = lower(?))",
                    chain.name(), account, account);
            jdbcTemplate.update("delete from ledger_balance where chain = ? and lower(account_id) = lower(?)", chain.name(), account);
            jdbcTemplate.update("delete from evm_nonce where chain = ? and lower(address) = lower(?)", chain.name(), account);
            jdbcTemplate.update("delete from chain_address where chain = ? and lower(address) = lower(?)",
                    chain.name(), account);
            jdbcTemplate.update("""
                            insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                                                      address, owner_address, derivation_path, wallet_role, enabled)
                            values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, true)
                            on conflict (chain, asset_symbol, address) do update set
                                tenant_id = excluded.tenant_id,
                                account_id = excluded.account_id,
                                user_id = excluded.user_id,
                                biz = excluded.biz,
                                address_index = excluded.address_index,
                                owner_address = excluded.owner_address,
                                derivation_path = excluded.derivation_path,
                                wallet_role = excluded.wallet_role,
                                enabled = true,
                                updated_at = now()
                            """,
                    TEST_TENANT_ID, chain.name(), nativeSymbol, account, actor.userId(), actor.derivationIndex(),
                    account, account, actor.path(), actor.role());
        }
    }

    private static void scanNativeDeposit(EvmDepositScanner scanner, ChainType chain, String nativeSymbol,
                                          int confirmations, TransactionReceipt receipt) throws Exception {
        List<DepositEvent> events = scanner.scanAndCreditNative(chain, nativeSymbol, LOCAL_RPC, confirmations,
                receipt.getBlockNumber().longValueExact());
        assertFalse(events.isEmpty(), "native deposit scanner must find the test deposit");
    }

    private static void scanTokenDeposit(EvmDepositScanner scanner, ChainType chain, int confirmations,
                                         TransactionReceipt receipt) throws Exception {
        List<DepositEvent> events = scanner.scanAndCreditErc20(chain, LOCAL_RPC, confirmations,
                receipt.getBlockNumber().longValueExact());
        assertFalse(events.isEmpty(), "ERC20 scanner must find the test deposit");
    }

    private static BigInteger reserveNonce(ChainJdbcRepository repository, Web3j web3j,
                                           ChainType chain, Actor actor) throws Exception {
        BigInteger nonce = pendingNonce(web3j, actor.credentials());
        repository.reserveNonce(EvmNonceRecord.builder()
                .chain(chain.name())
                .address(actor.address().toLowerCase(Locale.ROOT))
                .chainNonce(nonce.longValueExact())
                .reservedNonce(nonce.longValueExact())
                .status("RESERVED")
                .build());
        return nonce;
    }

    private static void recordEvmTx(ChainJdbcRepository repository, ChainType chain, String asset, String contract,
                                    String from, String to, BigDecimal amount, SignedTx tx) {
        if (tx == null) {
            return;
        }
        repository.recordEvmTransaction(EvmTransactionRecord.builder()
                .chain(chain.name())
                .txHash(tx.receipt().getTransactionHash())
                .fromAddress(from.toLowerCase(Locale.ROOT))
                .toAddress(to.toLowerCase(Locale.ROOT))
                .assetSymbol(asset)
                .contractAddress(contract)
                .amount(amount)
                .fee(fee(tx))
                .nonce(tx.nonce().longValueExact())
                .blockHeight(tx.receipt().getBlockNumber().longValueExact())
                .confirmations(1)
                .status("CONFIRMED")
                .rawPayload(tx.receipt().toString())
                .build());
    }

    private static void createOrder(JdbcTemplate jdbcTemplate, String orderNo, long userId, ChainType chain,
                                    String asset, String from, String to, BigDecimal amount) {
        jdbcTemplate.update("""
                        insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                                                     to_address, amount, fee, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 0, 'CREATED', now(), now())
                        """,
                TEST_TENANT_ID, orderNo, userId, chain.name(), asset,
                from.toLowerCase(Locale.ROOT), to.toLowerCase(Locale.ROOT), amount);
    }

    private static void updateOrder(JdbcTemplate jdbcTemplate, String orderNo, String status, String txHash,
                                    BigDecimal fee, String error) {
        jdbcTemplate.update("""
                        update withdrawal_order
                        set status = ?, tx_hash = coalesce(?, tx_hash), fee = ?, error_message = ?, updated_at = now()
                        where order_no = ?
                        """, status, txHash, fee, error, orderNo);
    }

    private static void assertDepositCount(JdbcTemplate jdbcTemplate, ChainType chain, String asset,
                                           String address, int expected) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*) from deposit_record
                        where chain = ? and asset_symbol = ? and lower(to_address) = lower(?)
                          and status = 'CREDITED' and credited = true
                        """, Integer.class, chain.name(), asset, address);
        assertEquals(expected, count);
    }

    private static int countNonConfirmedOrders(JdbcTemplate jdbcTemplate, ChainType chain) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*) from withdrawal_order
                        where chain = ? and order_no like ? and status <> 'CONFIRMED'
                        """, Integer.class, chain.name(), "EVM-STABILITY-" + chain.name() + "-%");
        return count == null ? 0 : count;
    }

    private static void assertAddressMatchesChain(JdbcTemplate jdbcTemplate, Web3j web3j, ChainType chain,
                                                  String nativeSymbol, List<TokenContract> tokens, Actor actor) throws Exception {
        assertBalanceEquals(getNativeBalance(web3j, actor.address()),
                ledgerOrZero(jdbcTemplate, chain, nativeSymbol, actor.address()));
        for (TokenContract token : tokens) {
            assertBalanceEquals(tokenBalance(web3j, token.address(), actor.address()),
                    ledgerOrZero(jdbcTemplate, chain, token.symbol(), actor.address()));
        }
        assertBalanceEquals(BigDecimal.ZERO, lockedOrZero(jdbcTemplate, chain, nativeSymbol, actor.address()));
        assertBalanceEquals(BigDecimal.ZERO, lockedOrZero(jdbcTemplate, chain, "USDT", actor.address()));
        assertBalanceEquals(BigDecimal.ZERO, lockedOrZero(jdbcTemplate, chain, "USDC", actor.address()));
    }

    private static List<TokenContract> tokenContracts(JdbcTemplate jdbcTemplate, ChainType chain) {
        return jdbcTemplate.query("""
                        select symbol, contract_address
                          from token_config
                         where chain = ? and enabled = true and standard = 'ERC20'
                         order by symbol
                        """,
                (rs, rowNum) -> new TokenContract(rs.getString("symbol"), rs.getString("contract_address")),
                chain.name());
    }

    private static Actors actors(ChainType chain) throws Exception {
        int base = 94_000 + chain.ordinal() * 100;
        return new Actors(
                actor("USER_A", 810_001L, base + 1),
                actor("USER_B", 810_002L, base + 2),
                actor("USER_C", 810_003L, base + 3),
                actor("USER_D", 810_004L, base + 4),
                actor("HOT", 810_090L, base + 90));
    }

    private static Actor actor(String label, long userId, int derivationIndex) throws Exception {
        String path = "m/44/2/1/" + derivationIndex + "/0";
        Bip32Node node = Bip32Node.decode(sig2Master())
                .getChild(44)
                .getChild(2)
                .getChild(1)
                .getChild(derivationIndex)
                .getChild(0);
        Credentials credentials = Credentials.create(node.getEcKey().getPrivateKeyAsHex());
        return new Actor(label, "STABILITY_" + label, userId, derivationIndex, path, credentials, credentials.getAddress());
    }

    private static String sig2Master() throws Exception {
        String fromProperty = System.getProperty("evm.sig2.master");
        if (isValidMasterKey(fromProperty)) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv("SW_SIG2_MASTER_KEY");
        if (isValidMasterKey(fromEnv)) {
            return fromEnv.trim();
        }
        Path yaml = projectRoot().resolve("backendservices/wallet-sig2/src/main/resources/application-test.yaml");
        for (String line : Files.readAllLines(yaml)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("masterKey:")) {
                String configured = trimmed.substring("masterKey:".length()).trim();
                if (isValidMasterKey(configured)) {
                    return configured;
                }
            }
        }
        return testMasterKey();
    }

    private static boolean isValidMasterKey(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return false;
        }
        try {
            Bip32Node.decode(value.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String testMasterKey() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x42);
        return Bip32Node.getMasterKey(seed).privSerialize(Bip32Node.TYPE_BITCOIN, true);
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("backendservices"))
                    && Files.exists(current.resolve("currency-sdks"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate project root");
    }

    private static String orderNo(ChainType chain, String user, String asset, String flow) {
        return "EVM-STABILITY-" + chain.name() + "-" + user + "-" + asset + "-" + flow;
    }

    private static TransactionReceipt sendUnlockedNative(Web3j web3j, String from, String to, BigDecimal amount) throws Exception {
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(
                        from, null, gasPrice, BigInteger.valueOf(21_000L), to, nativeToWei(amount))).send();
        return waitReceipt(web3j, sent);
    }

    private static TransactionReceipt sendUnlockedTokenCall(Web3j web3j, String from, String contract, String data) throws Exception {
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                        from, null, gasPrice, BigInteger.valueOf(120_000L), contract, BigInteger.ZERO, data)).send();
        return waitReceipt(web3j, sent);
    }

    private static SignedTx sendSignedNative(Web3j web3j, Credentials from, String to, BigDecimal amount,
                                             long chainId, BigInteger nonce, BigInteger gasLimit) throws Exception {
        BigDecimal before = getNativeBalance(web3j, from.getAddress());
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        RawTransaction raw = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, to, nativeToWei(amount));
        TransactionReceipt receipt = sendSigned(web3j, raw, from, chainId);
        BigDecimal after = getNativeBalance(web3j, from.getAddress());
        return new SignedTx(receipt, nonce, before.subtract(after).subtract(amount));
    }

    private static SignedTx sendSignedTokenTransfer(Web3j web3j, Credentials from, String contract, String to,
                                                    BigDecimal amount, long chainId, BigInteger nonce) throws Exception {
        BigDecimal before = getNativeBalance(web3j, from.getAddress());
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        RawTransaction raw = RawTransaction.createTransaction(nonce, gasPrice, BigInteger.valueOf(120_000L),
                contract, BigInteger.ZERO, encodeTransfer(to, amount));
        TransactionReceipt receipt = sendSigned(web3j, raw, from, chainId);
        BigDecimal after = getNativeBalance(web3j, from.getAddress());
        return new SignedTx(receipt, nonce, before.subtract(after));
    }

    private static TransactionReceipt sendSigned(Web3j web3j, RawTransaction raw, Credentials credentials, long chainId) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, credentials);
        EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        return waitReceipt(web3j, sent);
    }

    private static TransactionReceipt waitReceipt(Web3j web3j, EthSendTransaction sent) throws Exception {
        if (sent.hasError()) {
            throw new IllegalStateException("transaction failed before broadcast: " + sent.getError().getMessage());
        }
        String txHash = sent.getTransactionHash();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) {
                assertTrue(receipt.get().isStatusOK(), "tx receipt status must be successful: " + txHash);
                return receipt.get();
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("transaction receipt timeout: " + txHash);
    }

    private static BigInteger estimateNativeGas(Web3j web3j, String from, String to, BigDecimal amount) throws Exception {
        var tx = org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(
                from, null, null, null, to, nativeToWei(amount));
        return web3j.ethEstimateGas(tx).send().getAmountUsed();
    }

    private static BigInteger estimateTokenGas(Web3j web3j, String from, String contract, String to, BigDecimal amount) throws Exception {
        var tx = org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                from, null, null, null, contract, BigInteger.ZERO, encodeTransfer(to, amount));
        return web3j.ethEstimateGas(tx).send().getAmountUsed();
    }

    private static String encodeTransfer(String to, BigDecimal amount) {
        return FunctionEncoder.encode(new Function("transfer",
                List.of(new Address(to), new Uint256(tokenToUnits(amount))), List.of()));
    }

    private static BigDecimal tokenBalance(Web3j web3j, String token, String account) throws Exception {
        Function function = new Function("balanceOf",
                List.of(new Address(account)), List.of(TypeReference.create(Uint256.class)));
        EthCall response = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        account, token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        List<Type> decoded = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        BigInteger raw = (BigInteger) decoded.getFirst().getValue();
        return new BigDecimal(raw).divide(TOKEN_DECIMAL, 18, RoundingMode.DOWN);
    }

    private static BigDecimal getNativeBalance(Web3j web3j, String account) throws Exception {
        return weiToNative(web3j.ethGetBalance(account, DefaultBlockParameterName.LATEST).send().getBalance());
    }

    private static BigInteger pendingNonce(Web3j web3j, Credentials credentials) throws Exception {
        return web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
    }

    private static BigDecimal ledgerOrZero(JdbcTemplate jdbcTemplate, ChainType chain, String asset, String account) {
        List<BigDecimal> values = jdbcTemplate.queryForList("""
                        select available_balance from ledger_balance
                        where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                        """, BigDecimal.class, chain.name(), asset, account);
        return values.isEmpty() ? BigDecimal.ZERO : values.getFirst();
    }

    private static BigDecimal locked(JdbcTemplate jdbcTemplate, ChainType chain, String asset, String account) {
        return lockedOrZero(jdbcTemplate, chain, asset, account);
    }

    private static BigDecimal lockedOrZero(JdbcTemplate jdbcTemplate, ChainType chain, String asset, String account) {
        List<BigDecimal> values = jdbcTemplate.queryForList("""
                        select locked_balance from ledger_balance
                        where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                        """, BigDecimal.class, chain.name(), asset, account);
        return values.isEmpty() ? BigDecimal.ZERO : values.getFirst();
    }

    private static void assertBalanceEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.setScale(18, RoundingMode.DOWN).compareTo(actual.setScale(18, RoundingMode.DOWN)));
    }

    private static BigDecimal fee(SignedTx tx) {
        return tx.nativeFee();
    }

    private static BigInteger nativeToWei(BigDecimal amount) {
        return amount.multiply(WEI_PER_NATIVE).toBigIntegerExact();
    }

    private static BigInteger tokenToUnits(BigDecimal amount) {
        return amount.multiply(TOKEN_DECIMAL).toBigIntegerExact();
    }

    private static BigDecimal weiToNative(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_NATIVE, 18, RoundingMode.DOWN);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("evm.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("evm.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("evm.db.password", "wallet123"));
        return dataSource;
    }

    private record TokenContract(String symbol, String address) {
    }

    private record Actor(String label, String role, long userId, int derivationIndex,
                         String path, Credentials credentials, String address) {
    }

    private record Actors(Actor userA, Actor userB, Actor userC, Actor userD, Actor hotWallet) {
    }

    private record SignedTx(TransactionReceipt receipt, BigInteger nonce, BigDecimal nativeFee) {
    }
}
