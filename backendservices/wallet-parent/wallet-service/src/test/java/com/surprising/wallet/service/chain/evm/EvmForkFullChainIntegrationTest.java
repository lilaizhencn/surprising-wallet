package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.spongycastle.util.encoders.Hex;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvmForkFullChainIntegrationTest {
    private static final String LOCAL_RPC = "http://127.0.0.1:8545";
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");
    private static final BigDecimal TOKEN_DECIMAL = new BigDecimal("1000000");

    @Test
    void shouldExecuteNativeAndErc20ForkFullChain() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.fork.enabled"),
                "set -Devm.fork.enabled=true and start local Hardhat fork on 127.0.0.1:8545");

        ChainType chain = ChainType.valueOf(System.getProperty("evm.fork.chain", "ETH"));
        String nativeSymbol = System.getProperty("evm.native.symbol", "ETH");
        int confirmations = Integer.getInteger("evm.confirmations", 1);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbcTemplate);
        EvmDepositScanner scanner = new EvmDepositScanner(repository, new EvmLogScanner(), LOCAL_RPC, confirmations);

        Credentials wallet = walletCredentials(chain);
        Web3j web3j = Web3j.build(new HttpService(LOCAL_RPC));
        try {
            List<String> accounts = web3j.ethAccounts().send().getAccounts();
            assertTrue(accounts.size() >= 2, "Hardhat fork must expose unlocked accounts");
            String deployer = accounts.getFirst();
            String recipient = accounts.get(1);
            long chainId = web3j.ethChainId().send().getChainId().longValueExact();
            long expectedChainId = Long.getLong("evm.expected.chainId", chainId);
            assertEquals(expectedChainId, chainId, "fork chainId must match target chain");

            prepareDatabase(jdbcTemplate, chain, nativeSymbol, wallet.getAddress(), derivationPath(chain));
            List<TokenContract> tokens = tokenContracts(jdbcTemplate, chain);

            BigDecimal nativeDeposit = new BigDecimal("1.0");
            TransactionReceipt nativeDepositReceipt = sendUnlockedNative(web3j, deployer, wallet.getAddress(), nativeDeposit);
            List<DepositEvent> nativeDeposits = scanner.scanAndCreditNative(chain, nativeSymbol, LOCAL_RPC, confirmations,
                    nativeDepositReceipt.getBlockNumber().longValueExact());
            assertTrue(nativeDeposits.stream().anyMatch(event -> wallet.getAddress().equalsIgnoreCase(event.toAddress())));
            scanner.scanAndCreditNative(chain, nativeSymbol, LOCAL_RPC, confirmations,
                    nativeDepositReceipt.getBlockNumber().longValueExact());
            assertBalanceEquals(nativeDeposit, ledger(jdbcTemplate, chain, nativeSymbol, wallet.getAddress()));

            Map<TokenContract, BigDecimal> tokenDeposits = new LinkedHashMap<>();
            for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
                TokenContract token = tokens.get(tokenIndex);
                BigDecimal deposit = BigDecimal.valueOf(100L - tokenIndex * 25L);
                TransactionReceipt receipt = sendUnlockedTokenCall(web3j, deployer, token.address(),
                        encodeMint(wallet.getAddress(), deposit));
                scanner.scanAndCreditErc20(chain, LOCAL_RPC, confirmations,
                        receipt.getBlockNumber().longValueExact());
                scanner.scanAndCreditErc20(chain, LOCAL_RPC, confirmations,
                        receipt.getBlockNumber().longValueExact());
                assertBalanceEquals(deposit, ledger(jdbcTemplate, chain, token.symbol(), wallet.getAddress()));
                tokenDeposits.put(token, deposit);
            }

            BigDecimal nativeWithdraw = new BigDecimal("0.1");
            SignedTx nativeWithdrawTx = sendSignedNative(web3j, wallet, recipient, nativeWithdraw, chainId);
            BigDecimal nativeWithdrawFee = fee(nativeWithdrawTx);
            assertTrue(repository.debitLedgerBalance(chain.name(), nativeSymbol, wallet.getAddress(),
                    nativeWithdraw.add(nativeWithdrawFee)));

            BigDecimal nativeCollection = new BigDecimal("0.2");
            SignedTx nativeCollectionTx = sendSignedNative(web3j, wallet, deployer, nativeCollection, chainId);
            BigDecimal nativeCollectionFee = fee(nativeCollectionTx);
            assertTrue(repository.debitLedgerBalance(chain.name(), nativeSymbol, wallet.getAddress(),
                    nativeCollection.add(nativeCollectionFee)));

            for (Map.Entry<TokenContract, BigDecimal> entry : tokenDeposits.entrySet()) {
                TokenContract token = entry.getKey();
                BigDecimal deposit = entry.getValue();
                BigDecimal withdrawal = deposit.movePointLeft(1);
                SignedTx withdrawalTx = sendSignedTokenTransfer(
                        web3j, wallet, token.address(), recipient, withdrawal, chainId);
                assertTrue(repository.debitLedgerBalance(
                        chain.name(), token.symbol(), wallet.getAddress(), withdrawal));
                assertTrue(repository.debitLedgerBalance(
                        chain.name(), nativeSymbol, wallet.getAddress(), fee(withdrawalTx)));

                BigDecimal collection = deposit.subtract(withdrawal);
                SignedTx collectionTx = sendSignedTokenTransfer(
                        web3j, wallet, token.address(), deployer, collection, chainId);
                assertTrue(repository.debitLedgerBalance(
                        chain.name(), token.symbol(), wallet.getAddress(), collection));
                assertTrue(repository.debitLedgerBalance(
                        chain.name(), nativeSymbol, wallet.getAddress(), fee(collectionTx)));
            }

            assertBalanceEquals(getNativeBalance(web3j, wallet.getAddress()), ledger(jdbcTemplate, chain, nativeSymbol, wallet.getAddress()));
            for (TokenContract token : tokens) {
                assertBalanceEquals(tokenBalance(web3j, token.address(), wallet.getAddress()),
                        ledger(jdbcTemplate, chain, token.symbol(), wallet.getAddress()));
                assertEquals(BigDecimal.ZERO.setScale(18),
                        ledger(jdbcTemplate, chain, token.symbol(), wallet.getAddress()).setScale(18));
                assertFalse(repository.debitLedgerBalance(
                                chain.name(), token.symbol(), wallet.getAddress(), BigDecimal.ONE),
                        "ledger debit guard must reject " + token.symbol() + " double spend");
            }

            BigInteger pendingNonce = web3j.ethGetTransactionCount(wallet.getAddress(), DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            assertEquals(BigInteger.valueOf(2L + tokens.size() * 2L), pendingNonce,
                    "native withdrawal/collection plus two transfers per configured token");
        } finally {
            web3j.shutdown();
        }
    }

    private static void prepareDatabase(JdbcTemplate jdbcTemplate, ChainType chain, String nativeSymbol,
                                        String walletAddress, String derivationPath) {
        String account = walletAddress.toLowerCase(Locale.ROOT);
        jdbcTemplate.update("delete from deposit_record where chain = ? and lower(to_address) = lower(?)", chain.name(), account);
        jdbcTemplate.update("delete from evm_tx where chain = ? and (lower(to_address) = lower(?) or lower(from_address) = lower(?))",
                chain.name(), account, account);
        jdbcTemplate.update("delete from evm_token_transfer where chain = ? and (lower(to_address) = lower(?) or lower(from_address) = lower(?))",
                chain.name(), account, account);
        jdbcTemplate.update("delete from ledger_balance where chain = ? and lower(account_id) = lower(?)", chain.name(), account);
        jdbcTemplate.update("""
                        insert into chain_address(chain, asset_symbol, account_id, user_id, biz, address_index,
                                                  address, owner_address, derivation_path, wallet_role, enabled)
                        values (?, ?, ?, ?, 1, 0, ?, ?, ?, 'FORK_TEST', true)
                        on conflict (chain, asset_symbol, address) do update set
                            account_id = excluded.account_id,
                            user_id = excluded.user_id,
                            biz = excluded.biz,
                            address_index = excluded.address_index,
                            owner_address = excluded.owner_address,
                            derivation_path = excluded.derivation_path,
                            wallet_role = excluded.wallet_role,
                            enabled = true,
                            updated_at = now()
                        """, chain.name(), nativeSymbol, account, derivationIndex(chain), account, account,
                derivationPath);
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

    private static Credentials walletCredentials(ChainType chain) throws Exception {
        Bip32Node node = Bip32Node.decode(sig2Master())
                .getChild(44)
                .getChild(2)
                .getChild(1)
                .getChild(derivationIndex(chain))
                .getChild(0);
        return Credentials.create(node.getEcKey().getPrivateKeyAsHex());
    }

    private static String derivationPath(ChainType chain) {
        return "m/44/2/1/" + derivationIndex(chain) + "/0";
    }

    private static int derivationIndex(ChainType chain) {
        return 92001 + chain.ordinal();
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

    private static TransactionReceipt sendUnlockedNative(Web3j web3j, String from, String to, BigDecimal amount) throws Exception {
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(
                        from, null, gasPrice, BigInteger.valueOf(21_000L), to, ethToWei(amount))).send();
        return waitReceipt(web3j, sent);
    }

    private static TransactionReceipt sendUnlockedTokenCall(Web3j web3j, String from, String contract, String data) throws Exception {
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                        from, null, gasPrice, BigInteger.valueOf(100_000L), contract, BigInteger.ZERO, data)).send();
        return waitReceipt(web3j, sent);
    }

    private static SignedTx sendSignedNative(Web3j web3j, Credentials from, String to,
                                             BigDecimal amount, long chainId) throws Exception {
        BigDecimal before = getNativeBalance(web3j, from.getAddress());
        BigInteger nonce = pendingNonce(web3j, from);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        RawTransaction raw = RawTransaction.createEtherTransaction(nonce, gasPrice, BigInteger.valueOf(21_000L),
                to, ethToWei(amount));
        TransactionReceipt receipt = sendSigned(web3j, raw, from, chainId);
        BigDecimal after = getNativeBalance(web3j, from.getAddress());
        return new SignedTx(receipt, before.subtract(after).subtract(amount));
    }

    private static SignedTx sendSignedTokenTransfer(Web3j web3j, Credentials from, String contract,
                                                    String to, BigDecimal amount, long chainId) throws Exception {
        BigDecimal before = getNativeBalance(web3j, from.getAddress());
        BigInteger nonce = pendingNonce(web3j, from);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        RawTransaction raw = RawTransaction.createTransaction(nonce, gasPrice, BigInteger.valueOf(100_000L),
                contract, BigInteger.ZERO, encodeTransfer(to, amount));
        TransactionReceipt receipt = sendSigned(web3j, raw, from, chainId);
        BigDecimal after = getNativeBalance(web3j, from.getAddress());
        return new SignedTx(receipt, before.subtract(after));
    }

    private static TransactionReceipt sendSigned(Web3j web3j, RawTransaction raw, Credentials credentials,
                                                 long chainId) throws Exception {
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

    private static BigInteger pendingNonce(Web3j web3j, Credentials credentials) throws Exception {
        return web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
    }

    private static String encodeMint(String to, BigDecimal amount) {
        return FunctionEncoder.encode(new Function("mint",
                List.of(new Address(to), new Uint256(tokenToUnits(amount))), List.of()));
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
        return weiToEth(web3j.ethGetBalance(account, DefaultBlockParameterName.LATEST).send().getBalance());
    }

    private static BigDecimal ledger(JdbcTemplate jdbcTemplate, ChainType chain, String asset, String account) {
        return jdbcTemplate.queryForObject("""
                        select available_balance
                        from ledger_balance
                        where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                        """, BigDecimal.class, chain.name(), asset, account);
    }

    private static void assertBalanceEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.setScale(18, RoundingMode.DOWN).compareTo(actual.setScale(18, RoundingMode.DOWN)));
    }

    private static BigDecimal fee(SignedTx tx) {
        return tx.nativeFee();
    }

    private static BigInteger ethToWei(BigDecimal amount) {
        return amount.multiply(WEI_PER_ETH).toBigIntegerExact();
    }

    private static BigInteger tokenToUnits(BigDecimal amount) {
        return amount.multiply(TOKEN_DECIMAL).toBigIntegerExact();
    }

    private static BigDecimal weiToEth(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_ETH, 18, RoundingMode.DOWN);
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

    private record SignedTx(TransactionReceipt receipt, BigDecimal nativeFee) {
    }
}
