package com.surprising.wallet.service.chain.solana;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.service.chain.evm.EvmDepositScanner;
import com.surprising.wallet.service.chain.evm.EvmLogScanner;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexLiveUserFundingTest {
    private static final String USER_EMAIL = "lilaizhencn@gmail.com";
    private static final String BASE_USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";
    private static final String SOLANA_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";

    @Test
    void fundUserThroughExternalAddresses() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("codex.live.user-funding"),
                "set -Dcodex.live.user-funding=true to broadcast funded testnet transactions");

        JdbcTemplate jdbc = jdbc();
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        long userId = userId(jdbc, USER_EMAIL);
        StringBuilder report = new StringBuilder();
        report.append("Codex live funding report\n");
        report.append("user=").append(USER_EMAIL).append(" id=").append(userId).append('\n');
        report.append("startedAt=").append(Instant.now()).append('\n');

        fundEth(jdbc, repository, userId, report);
        fundBaseUsdc(jdbc, repository, userId, report);
        fundSolana(jdbc, repository, userId, report);

        Path out = Path.of("target", "codex-live-user-funding-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);
    }

    @Test
    void scanKnownFundedUserDeposits() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("codex.live.user-scan"),
                "set -Dcodex.live.user-scan=true to scan the known live deposit blocks");

        JdbcTemplate jdbc = jdbc();
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        StringBuilder report = new StringBuilder();
        report.append("Codex live scan report\n");
        scanEvmBlock(jdbc, repository, ChainType.ETH,
                Long.getLong("codex.eth.deposit-block", 11145320L), report);
        scanEvmBlock(jdbc, repository, ChainType.BASE,
                Long.getLong("codex.base.deposit-block", 43362796L), report);

        String seed = env("SW_ED25519_SEED", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        SolanaRpcClient rpc = new SolanaRpcClient(new ObjectMapper(), rpcUrl(jdbc, "SOLANA", "devnet"));
        SolanaAddressService addresses = new SolanaAddressService(new SolanaKeyService(seed), repository);
        SolanaDepositScanner solanaScanner = new SolanaDepositScanner(rpc, repository);
        setPrivateField(solanaScanner, "addressService", addresses);
        var solanaEvents = solanaScanner.scanAndCredit();
        report.append("SOLANA_EVENTS=").append(solanaEvents.size()).append('\n');

        Path out = Path.of("target", "codex-live-user-scan-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);
    }

    private void fundEth(JdbcTemplate jdbc, ChainJdbcRepository repository, long userId, StringBuilder report)
            throws Exception {
        AccountChainProfile profile = repository.findProfileByChain("ETH").orElseThrow();
        String rpcUrl = rpcUrl(jdbc, "ETH", profile.getNetwork());
        ChainAddressRecord hot = repository.listDefaultHotAddressCandidates("ETH", "ETH").getFirst();
        ChainAddressRecord user = repository.findChainAddress("ETH", "ETH", userId, 0, 0, "DEPOSIT").orElseThrow();
        Credentials hotCredentials = credentials(profile, hot);
        Credentials external = Credentials.create(Keys.createEcKeyPair());

        Web3j web3j = Web3j.build(new HttpService(rpcUrl));
        try {
            String hotToExternal = sendNative(web3j, profile.getChainId(), hotCredentials,
                    external.getAddress(), new BigDecimal("0.008"));
            TransactionReceipt firstReceipt = waitReceipt(web3j, hotToExternal, Duration.ofMinutes(4));
            String externalToUser = sendNative(web3j, profile.getChainId(), external,
                    user.getAddress(), new BigDecimal("0.005"));
            TransactionReceipt depositReceipt = waitReceipt(web3j, externalToUser, Duration.ofMinutes(4));
            rewindEvmScanner(jdbc, "ETH", depositReceipt.getBlockNumber().longValue());
            report.append("ETH_EXTERNAL=").append(external.getAddress()).append('\n');
            report.append("ETH_HOT_TO_EXTERNAL_TX=").append(hotToExternal)
                    .append(" block=").append(firstReceipt.getBlockNumber()).append('\n');
            report.append("ETH_EXTERNAL_TO_USER_TX=").append(externalToUser)
                    .append(" block=").append(depositReceipt.getBlockNumber()).append('\n');
        } finally {
            web3j.shutdown();
        }
    }

    private void fundBaseUsdc(JdbcTemplate jdbc, ChainJdbcRepository repository, long userId, StringBuilder report)
            throws Exception {
        AccountChainProfile profile = repository.findProfileByChain("BASE").orElseThrow();
        String rpcUrl = rpcUrl(jdbc, "BASE", profile.getNetwork());
        ChainAddressRecord hot = repository.listDefaultHotAddressCandidates("BASE", "ETH_BASE").getFirst();
        ChainAddressRecord user = repository.findChainAddress("BASE", "USDC", userId, 0, 0, "DEPOSIT").orElseThrow();
        Credentials hotCredentials = credentials(profile, hot);
        Credentials external = Credentials.create(Keys.createEcKeyPair());

        Web3j web3j = Web3j.build(new HttpService(rpcUrl));
        try {
            String gasTx = sendNative(web3j, profile.getChainId(), hotCredentials,
                    external.getAddress(), new BigDecimal("0.00002"));
            TransactionReceipt gasReceipt = waitReceipt(web3j, gasTx, Duration.ofMinutes(4));
            String fundTokenTx = sendErc20(web3j, profile.getChainId(), hotCredentials, BASE_USDC,
                    external.getAddress(), new BigDecimal("0.4"), 6);
            TransactionReceipt tokenReceipt = waitReceipt(web3j, fundTokenTx, Duration.ofMinutes(4));
            String depositTx = sendErc20(web3j, profile.getChainId(), external, BASE_USDC,
                    user.getAddress(), new BigDecimal("0.2"), 6);
            TransactionReceipt depositReceipt = waitReceipt(web3j, depositTx, Duration.ofMinutes(4));
            rewindEvmScanner(jdbc, "BASE", depositReceipt.getBlockNumber().longValue());
            report.append("BASE_EXTERNAL=").append(external.getAddress()).append('\n');
            report.append("BASE_GAS_TX=").append(gasTx)
                    .append(" block=").append(gasReceipt.getBlockNumber()).append('\n');
            report.append("BASE_USDC_HOT_TO_EXTERNAL_TX=").append(fundTokenTx)
                    .append(" block=").append(tokenReceipt.getBlockNumber()).append('\n');
            report.append("BASE_USDC_EXTERNAL_TO_USER_TX=").append(depositTx)
                    .append(" block=").append(depositReceipt.getBlockNumber()).append('\n');
        } finally {
            web3j.shutdown();
        }
    }

    private void fundSolana(JdbcTemplate jdbc, ChainJdbcRepository repository, long userId, StringBuilder report)
            throws Exception {
        String seed = env("SW_ED25519_SEED", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        String rpcUrl = rpcUrl(jdbc, "SOLANA", "devnet");
        SolanaRpcClient rpc = new SolanaRpcClient(new ObjectMapper(), rpcUrl);
        SolanaKeyService keys = new SolanaKeyService(seed);
        SolanaAddressService addresses = new SolanaAddressService(keys, repository);
        SolanaTransactionService transactions = new SolanaTransactionService(rpc, keys, addresses, repository);
        Account hot = keys.account(0, 0, 0);
        Account external = keys.account(99_000_000L + Math.abs(System.currentTimeMillis() % 100_000L));
        ChainAddressRecord userSol = repository.findChainAddress("SOLANA", "SOL", userId, 0, 0, "DEPOSIT")
                .orElseThrow();

        String hotToExternal = sendSol(rpc, hot, external.getPublicKeyBase58(), 30_000_000L);
        transactions.requireSuccessfulConfirmation(hotToExternal, Duration.ofMinutes(2));
        String externalToUser = sendSol(rpc, external, userSol.getAddress(), 10_000_000L);
        transactions.requireSuccessfulConfirmation(externalToUser, Duration.ofMinutes(2));
        String tokenHotToExternal = sendSpl(rpc, addresses, hot, SOLANA_USDC,
                external.getPublicKeyBase58(), 400_000L, 6);
        transactions.requireSuccessfulConfirmation(tokenHotToExternal, Duration.ofMinutes(2));
        String tokenExternalToUser = sendSpl(rpc, addresses, external, SOLANA_USDC,
                userSol.getAddress(), 200_000L, 6);
        transactions.requireSuccessfulConfirmation(tokenExternalToUser, Duration.ofMinutes(2));

        report.append("SOLANA_EXTERNAL=").append(external.getPublicKeyBase58()).append('\n');
        report.append("SOLANA_HOT_TO_EXTERNAL_TX=").append(hotToExternal).append('\n');
        report.append("SOLANA_EXTERNAL_TO_USER_TX=").append(externalToUser).append('\n');
        report.append("SOLANA_USDC_HOT_TO_EXTERNAL_TX=").append(tokenHotToExternal).append('\n');
        report.append("SOLANA_USDC_EXTERNAL_TO_USER_TX=").append(tokenExternalToUser).append('\n');
    }

    private void scanEvmBlock(JdbcTemplate jdbc, ChainJdbcRepository repository, ChainType chainType,
                              long blockHeight, StringBuilder report) throws Exception {
        AccountChainProfile profile = repository.findProfileByChain(chainType.name()).orElseThrow();
        EvmDepositScanner scanner = new EvmDepositScanner(repository, new EvmLogScanner(),
                rpcUrl(jdbc, chainType.name(), profile.getNetwork()), profile.getDepositConfirmations());
        var nativeEvents = scanner.scanAndCreditNative(chainType, blockHeight);
        var tokenEvents = scanner.scanAndCreditErc20(chainType, blockHeight);
        report.append(chainType.name()).append("_BLOCK=").append(blockHeight)
                .append(" nativeEvents=").append(nativeEvents.size())
                .append(" tokenEvents=").append(tokenEvents.size())
                .append('\n');
    }

    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = Bip32Node.decode(env("SW_SIG2_MASTER_KEY", ""))
                .getChild(44)
                .getChild(ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType()))
                .getChild(from.getBiz())
                .getChild(Math.toIntExact(from.getUserId()))
                .getChild(Math.toIntExact(from.getAddressIndex()))
                .getEcKey();
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }

    private String sendNative(Web3j web3j, long chainId, Credentials from, String to, BigDecimal amount)
            throws Exception {
        BigInteger nonce = nonce(web3j, from.getAddress());
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger value = amount.movePointRight(18).toBigIntegerExact();
        RawTransaction tx = RawTransaction.createEtherTransaction(
                nonce, gasPrice, BigInteger.valueOf(21_000L), to, value);
        return sendRaw(web3j, chainId, from, tx);
    }

    private String sendErc20(Web3j web3j, long chainId, Credentials from, String contract,
                             String to, BigDecimal amount, int decimals) throws Exception {
        BigInteger nonce = nonce(web3j, from.getAddress());
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger atomic = amount.movePointRight(decimals).toBigIntegerExact();
        Function transfer = new Function("transfer",
                List.<Type>of(new Address(to), new Uint256(atomic)),
                List.of());
        RawTransaction tx = RawTransaction.createTransaction(
                nonce, gasPrice, BigInteger.valueOf(85_000L), contract, BigInteger.ZERO,
                FunctionEncoder.encode(transfer));
        return sendRaw(web3j, chainId, from, tx);
    }

    private String sendRaw(Web3j web3j, long chainId, Credentials from, RawTransaction tx) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, from);
        EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        return sent.getTransactionHash();
    }

    private BigInteger nonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().getTransactionCount();
    }

    private TransactionReceipt waitReceipt(Web3j web3j, String txHash, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash)
                    .send()
                    .getTransactionReceipt();
            if (receipt.isPresent() && receipt.get().getBlockNumber() != null) {
                assertTrue(receipt.get().isStatusOK(), "transaction failed: " + txHash);
                return receipt.get();
            }
            Thread.sleep(2_000L);
        }
        throw new IllegalStateException("receipt timeout: " + txHash);
    }

    private String sendSol(SolanaRpcClient rpc, Account from, String to, long lamports) {
        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.transfer(
                        from.getPublicKey(), new PublicKey(to), lamports));
        return signAndSend(rpc, transaction, List.of(from));
    }

    private String sendSpl(SolanaRpcClient rpc, SolanaAddressService addresses, Account from, String mintAddress,
                           String toOwnerAddress, long atomicAmount, int decimals) {
        PublicKey mint = new PublicKey(mintAddress);
        PublicKey toOwner = new PublicKey(toOwnerAddress);
        String destinationAta = addresses.associatedTokenAddress(toOwnerAddress, mintAddress);
        Transaction transaction = new Transaction();
        if (rpc.getAccountInfo(destinationAta).isNull()) {
            transaction.addInstruction(AssociatedTokenProgram.createIdempotent(
                    from.getPublicKey(), toOwner, mint));
        }
        transaction.addInstruction(TokenProgram.transferChecked(
                new PublicKey(addresses.associatedTokenAddress(from.getPublicKeyBase58(), mintAddress)),
                new PublicKey(destinationAta),
                atomicAmount,
                (byte) decimals,
                from.getPublicKey(),
                mint));
        return signAndSend(rpc, transaction, List.of(from));
    }

    private String signAndSend(SolanaRpcClient rpc, Transaction transaction, List<Account> signers) {
        transaction.setRecentBlockHash(rpc.getLatestBlockhash());
        transaction.sign(signers);
        return rpc.sendTransaction(transaction.serialize());
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("CODEX_DB_URL", "jdbc:postgresql://172.31.6.134:5432/wallet"),
                env("CODEX_DB_USER", "wallet"),
                env("CODEX_DB_PASSWORD", "wallet123"));
        return new JdbcTemplate(dataSource);
    }

    private long userId(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject(
                "select id from wallet_user where lower(email)=lower(?) and status='ACTIVE'",
                Long.class, email);
    }

    private String rpcUrl(JdbcTemplate jdbc, String chain, String network) {
        return jdbc.queryForObject("""
                        select rpc_url
                          from chain_rpc_node
                         where chain = ?
                           and lower(network) = lower(?)
                           and environment = 'test2'
                           and purpose = 'rpc'
                           and enabled = true
                         order by priority, id
                         limit 1
                        """, String.class, chain, network);
    }

    private void rewindEvmScanner(JdbcTemplate jdbc, String chain, long blockHeight) {
        long previous = Math.max(0L, blockHeight - 1L);
        jdbc.update("""
                        insert into chain_scan_height(chain, scanner_name, best_height, safe_height, status,
                                                      created_at, updated_at)
                        values (?, 'native-evm', ?, ?, 'ACTIVE', now(), now())
                        on conflict(chain, scanner_name) do update set
                            best_height = excluded.best_height,
                            safe_height = excluded.safe_height,
                            updated_at = now()
                        """, chain, previous, previous);
        jdbc.update("""
                        insert into chain_scan_height(chain, scanner_name, best_height, safe_height, status,
                                                      created_at, updated_at)
                        values (?, 'erc20-evm', ?, ?, 'ACTIVE', now(), now())
                        on conflict(chain, scanner_name) do update set
                            best_height = excluded.best_height,
                            safe_height = excluded.safe_height,
                            updated_at = now()
                        """, chain, previous, previous);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
