package com.surprising.wallet.chain.evm;

import com.surprising.wallet.chain.WalletKeyTestFixture;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.AccountSecp256k1KeyService;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 EVM 账户交易服务在本地逐链矩阵中的真实签名、费用、nonce、广播和交易记录闭环。
 */
class EvmForkTransactionServiceIntegrationTest {
    /** 本地 Hardhat JSON-RPC 地址。 */
    private static final String LOCAL_RPC = "http://127.0.0.1:8545";
    /** 测试使用的原生币最小单位换算因子。 */
    private static final BigDecimal NATIVE_UNIT = new BigDecimal("1000000000000000000");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    /**
     * 验证 EVM 交易服务可以发送原生币和 ERC-20，并把链上结果写入交易记录。
     */
    @Test
    void shouldSendAndConfirmNativeAndTokenThroughAccountTransactionService() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.service.enabled"),
                "set -Devm.service.enabled=true and start local Hardhat on 127.0.0.1:8545");

        ChainType chain = ChainType.valueOf(System.getProperty("evm.fork.chain", "ETH"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainRepositoryFixture repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
        AccountChainProfile originalProfile = repository.chainProfileRepository.findAccountByChain(chain.name()).orElseThrow();
        String originalNetwork = originalProfile.getNetwork();
        Web3j web3j = Web3j.build(new HttpService(LOCAL_RPC));
        EvmHttpServiceFactory httpServices = new EvmHttpServiceFactory();
        var context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(WithdrawalOrderRepository.class, () -> repository.withdrawalOrderRepository);
        context.registerBean(LedgerBalanceRepository.class, () -> repository.ledgerBalanceRepository);
        context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(com.surprising.wallet.repository.CollectionRecordRepository.class, () -> repository.collectionRecordRepository);
        context.registerBean(com.surprising.wallet.repository.EvmTransactionRepository.class, () -> repository.evmTransactionRepository);
        context.registerBean(com.surprising.wallet.repository.EvmNonceRepository.class, () -> new com.surprising.wallet.repository.EvmNonceRepository(jdbc));
        context.registerBean(com.surprising.wallet.repository.CustodyAssetRecoveryRepository.class, () -> new com.surprising.wallet.repository.CustodyAssetRecoveryRepository(jdbc));
        context.registerBean(com.surprising.wallet.service.EvmSubmissionService.class);
        context.refresh();
        try {
            List<String> accounts = web3j.ethAccounts().send().getAccounts();
            assertTrue(accounts.size() >= 3, "Hardhat must expose deployer and recipients");
            long chainId = web3j.ethChainId().send().getChainId().longValueExact();
            assertEquals(Long.getLong("evm.expected.chainId", chainId), chainId,
                    "Hardhat chainId must match the selected chain profile");

            // Hardhat 没有 OP Stack/Arbitrum 的生产预言机合约，测试服务的本地费用旁路必须显式标记。
            jdbc.update("update chain_profile set network = 'local', updated_at = now() "
                            + "where chain = ? and network = ?",
                    chain.name(), originalNetwork);
            AccountChainProfile localProfile = repository.chainProfileRepository.findAccountByChain(chain.name()).orElseThrow();
            AccountSecp256k1KeyService keyService =
                    new AccountSecp256k1KeyService(WalletKeyTestFixture.provider());
            ChainAddressRecord from = addressRecord(localProfile, chain);
            Credentials credentials = credentials(keyService, localProfile, from);
            from.setAddress(credentials.getAddress());
            from.setAccountId(credentials.getAddress().toLowerCase(java.util.Locale.ROOT));
            from.setOwnerAddress(credentials.getAddress());

            ChainRpcNodeService rpcNodes = fixedRpcNodes(localProfile);
            EvmAccountTransactionService service = new EvmAccountTransactionService(
                    repository.withdrawalOrderRepository,
                    repository.collectionRecordRepository,
                    repository.evmTransactionRepository,
                    repository.chainProfileRepository,
                    repository.chainAssetRepository,
                    rpcNodes,
                    keyService,
                    new EvmTransactionBuilder(),
                    httpServices,
                    context.getBean(WithdrawalSettlementService.class),
                    context.getBean(com.surprising.wallet.service.EvmSubmissionService.class),
                    new com.surprising.wallet.repository.CustodyAssetRecoveryRepository(jdbc));

            sendUnlockedNative(web3j, accounts.getFirst(), from.getAddress(), new BigDecimal("2"));
            var nativeOperation = prepareOperation(repository,jdbc,localProfile,from,localProfile.getNativeSymbol(),accounts.get(1),new BigDecimal("0.25"),new BigDecimal("0.01"));
            String nativeHash = service.sendNative(
                    nativeOperation, chain.name(), from, accounts.get(1), new BigDecimal("0.25"));
            TransactionReceipt nativeReceipt = waitReceipt(web3j, nativeHash);
            assertTrue(nativeReceipt.isStatusOK(), "service native transaction must succeed");
            assertSentTransaction(jdbc, chain, nativeHash, localProfile.getNativeSymbol(), null);
            confirmAndAssertOrderDebit(service, repository, jdbc, localProfile, from,
                    localProfile.getNativeSymbol(), accounts.get(1), nativeHash, new BigDecimal("0.25"), new BigDecimal("0.01"));

            List<TokenDefinition> configuredTokens = repository.tokenConfigRepository.listDefinitions(chain.name(), originalNetwork);
            assertFalse(configuredTokens.isEmpty(), "the local matrix must exercise an enabled ERC-20");
            TokenDefinition token = configuredTokens.getFirst();
            sendUnlockedTokenCall(web3j, accounts.getFirst(), token.getContractAddress(),
                    encodeMint(from.getAddress(), new BigDecimal("100"), token.getDecimals()));
            var tokenOperation = prepareOperation(repository,jdbc,localProfile,from,token.getSymbol(),accounts.get(2),new BigDecimal("25"),new BigDecimal("0.5"));
            String tokenHash = service.sendToken(
                    tokenOperation, chain.name(), from, token, accounts.get(2), new BigDecimal("25"));
            TransactionReceipt tokenReceipt = waitReceipt(web3j, tokenHash);
            assertTrue(tokenReceipt.isStatusOK(), "service token transaction must succeed");
            assertSentTransaction(jdbc, chain, tokenHash, token.getSymbol(), token.getContractAddress());
            confirmAndAssertOrderDebit(service, repository, jdbc, localProfile, from,
                    token.getSymbol(), accounts.get(2), tokenHash, new BigDecimal("25"), new BigDecimal("0.5"));
            assertEquals(new BigDecimal("75").setScale(token.getDecimals()),
                    tokenBalance(web3j, token.getContractAddress(), from.getAddress(), token.getDecimals()));
            assertEquals(new BigDecimal("25").setScale(token.getDecimals()),
                    tokenBalance(web3j, token.getContractAddress(), accounts.get(2), token.getDecimals()));

            BigInteger pendingNonce = web3j.ethGetTransactionCount(
                    from.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            assertEquals(BigInteger.TWO, pendingNonce,
                    "service must reserve consecutive nonces for configured asset transfers");
        } finally {
            context.close();
            httpServices.close();
            jdbc.update("update chain_profile set network = ?, updated_at = now() "
                            + "where chain = ? and network = 'local'",
                    originalNetwork, chain.name());
            web3j.shutdown();
        }
    }

    private static com.surprising.wallet.service.EvmSubmissionService.Operation prepareOperation(
            ChainRepositoryFixture repository,JdbcTemplate jdbc,AccountChainProfile profile,ChainAddressRecord from,
            String symbol,String recipient,BigDecimal amount,BigDecimal fee) {
        String account="service-customer-"+profile.getChain();
        String orderNo="service-confirm-"+profile.getChain()+"-"+symbol;
        BigDecimal debit=amount.add(fee);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,?,?,?,?,0,?)",
                TENANT,profile.getChain(),symbol,account,debit.add(BigDecimal.ONE),debit.add(BigDecimal.ONE));
        repository.withdrawalOrderRepository.createForTenant(TENANT,orderNo,from.getUserId(),profile.getChain(),symbol,
                from.getAddress(),account,recipient,amount,fee);
        assertTrue(repository.ledgerBalanceRepository.freeze(profile.getChain(),symbol,account,debit,TENANT));
        assertEquals(1,repository.withdrawalOrderRepository.updateStatus(TENANT,profile.getChain(),orderNo,"FROZEN",from.getAddress(),null,null));
        assertEquals(1,repository.withdrawalOrderRepository.claimSigningEvm(TENANT,profile.getChain(),orderNo,from.getAddress()));
        return new com.surprising.wallet.service.EvmSubmissionService.Operation(TENANT,orderNo,com.surprising.wallet.service.EvmSubmissionService.Kind.WITHDRAWAL);
    }

    /** Exercise the real receipt path and normal Spring settlement transaction, including a replay. */
    private static void confirmAndAssertOrderDebit(EvmAccountTransactionService service,
            ChainRepositoryFixture repository, JdbcTemplate jdbc, AccountChainProfile profile,
            ChainAddressRecord from, String symbol, String recipient, String hash,
            BigDecimal amount, BigDecimal customerFee) throws Exception {
        String account = "service-customer-" + profile.getChain();
        String orderNo = "service-confirm-" + profile.getChain() + "-" + symbol;
        assertEquals(1, repository.withdrawalOrderRepository.updateStatus(TENANT, profile.getChain(), orderNo,
                "SENT", from.getAddress(), hash, null));
        var mining = new HttpService(LOCAL_RPC);
        try {
            var response = new Request<>("hardhat_mine", List.of(Numeric.encodeQuantity(
                    BigInteger.valueOf(Math.max(1, profile.getWithdrawConfirmations())))), mining, Response.class).send();
            assertFalse(response.hasError(), "local blocks must satisfy the actual profile confirmation threshold");
        } finally {
            mining.close();
        }
        assertTrue(service.confirmWithdrawal(TENANT, profile.getChain(), orderNo));
        assertFalse(service.confirmWithdrawal(TENANT, profile.getChain(), orderNo));
        var order = repository.withdrawalOrderRepository.find(profile.getChain(), orderNo, TENANT).orElseThrow();
        assertEquals("CONFIRMED", order.getStatus());
        assertEquals(hash, order.getTxHash());
        var balance = repository.ledgerBalanceRepository.find(TENANT, profile.getChain(), symbol, account).orElseThrow();
        assertEquals(0, BigDecimal.ONE.compareTo(balance.getAvailableBalance()));
        assertEquals(0, balance.getLockedBalance().signum());
        assertEquals(0, BigDecimal.ONE.compareTo(balance.getTotalBalance()));
        assertEquals("CONFIRMED", jdbc.queryForObject("select status from evm_tx where chain=? and tx_hash=?",
                String.class, profile.getChain(), hash));
    }

    /**
     * 创建与生产账户派生路径一致的测试地址记录。
     */
    private static ChainAddressRecord addressRecord(AccountChainProfile profile, ChainType chain) {
        return ChainAddressRecord.builder()
                .tenantId(TENANT)
                .chain(chain.name())
                .assetSymbol(profile.getNativeSymbol())
                .userId(93001L + chain.ordinal())
                .biz(1)
                .addressIndex(0L)
                .walletRole("FORK_SERVICE_TEST")
                .derivationPath("m/44/60/1/" + (93001L + chain.ordinal()) + "/0")
                .enabled(true)
                .build();
    }

    /**
     * 使用与生产签名服务完全相同的 BIP44 路径生成 EVM 凭证。
     */
    private static Credentials credentials(AccountSecp256k1KeyService keyService,
                                           AccountChainProfile profile, ChainAddressRecord address) {
        ECKey key = keyService.key(profile, address);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(key.getPrivKey(), 64));
    }

    /**
     * 创建固定本地 RPC 节点适配器，避免测试依赖真实环境的 RPC 配置。
     */
    private static ChainRpcNodeService fixedRpcNodes(AccountChainProfile profile) {
        ChainRpcNode node = ChainRpcNode.builder()
                .chain(profile.getChain())
                .network(profile.getNetwork())
                .environment("evm-service-test")
                .nodeLabel("hardhat-local")
                .purpose("rpc")
                .connectionType("HTTP_JSON_RPC")
                .rpcUrl(LOCAL_RPC)
                .priority(1)
                .enabled(true)
                .build();
        return com.surprising.wallet.testutil.RpcTestSupport.nodes(node);
    }

    /**
     * 通过 Hardhat 的解锁账户向测试钱包转入原生 Gas 币。
     */
    private static String sendUnlockedNative(Web3j web3j, String from, String to,
                                             BigDecimal amount) throws Exception {
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(
                        from, null, web3j.ethGasPrice().send().getGasPrice(),
                        BigInteger.valueOf(21_000L), to, nativeToAtomic(amount))).send();
        return waitReceipt(web3j, sent.getTransactionHash()).getTransactionHash();
    }

    /**
     * 通过 Hardhat 的解锁账户调用 MockERC20 方法。
     */
    private static String sendUnlockedTokenCall(Web3j web3j, String from, String contract,
                                                String data) throws Exception {
        EthSendTransaction sent = web3j.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                        from, null, web3j.ethGasPrice().send().getGasPrice(),
                        BigInteger.valueOf(100_000L), contract, BigInteger.ZERO, data)).send();
        return waitReceipt(web3j, sent.getTransactionHash()).getTransactionHash();
    }

    /**
     * 等待交易进入区块并校验节点没有返回失败回执。
     */
    private static TransactionReceipt waitReceipt(Web3j web3j, String txHash) throws Exception {
        assertNotNull(txHash, "RPC must return transaction hash");
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) {
                assertTrue(receipt.get().isStatusOK(), "transaction must be mined successfully: " + txHash);
                return receipt.get();
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("transaction receipt timeout: " + txHash);
    }

    /**
     * 校验服务写入的 EVM 交易记录保留了资产、合约和发送状态。
     */
    private static void assertSentTransaction(JdbcTemplate jdbc, ChainType chain, String txHash,
                                              String symbol, String contract) {
        Map<String, Object> row = jdbc.queryForMap(
                "select asset_symbol, contract_address, status, nonce, fee from evm_tx "
                        + "where chain = ? and tx_hash = ?", chain.name(), txHash);
        assertEquals(symbol, row.get("asset_symbol"));
        assertEquals(contract, row.get("contract_address"));
        assertEquals("SENT", row.get("status"));
        assertTrue(((Number) row.get("nonce")).longValue() >= 0, "recorded nonce must be non-negative");
        assertTrue(((BigDecimal) row.get("fee")).signum() > 0, "recorded fee must be positive");
    }

    /**
     * 编码 MockERC20 的 mint 调用参数。
     */
    private static String encodeMint(String to, BigDecimal amount, int decimals) {
        return FunctionEncoder.encode(new Function("mint",
                List.of(new Address(to), new Uint256(tokenToAtomic(amount, decimals))), List.of()));
    }

    /**
     * 查询 ERC-20 余额并转换为配置的小数位。
     */
    private static BigDecimal tokenBalance(Web3j web3j, String contract, String account,
                                           int decimals) throws Exception {
        Function function = new Function("balanceOf", List.of(new Address(account)),
                List.of(TypeReference.create(Uint256.class)));
        EthCall response = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        account, contract, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        List<Type> values = org.web3j.abi.FunctionReturnDecoder.decode(
                response.getValue(), function.getOutputParameters());
        return new BigDecimal((BigInteger) values.getFirst().getValue())
                .movePointLeft(decimals).setScale(decimals, RoundingMode.UNNECESSARY);
    }

    /**
     * 把原生币金额转换为链上最小单位。
     */
    private static BigInteger nativeToAtomic(BigDecimal amount) {
        return amount.multiply(NATIVE_UNIT).toBigIntegerExact();
    }

    /**
     * 把代币金额转换为链上最小单位。
     */
    private static BigInteger tokenToAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).toBigIntegerExact();
    }

    /**
     * 创建使用本机 PostgreSQL 隔离数据库的测试数据源。
     */
    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("evm.db.url",
                "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("evm.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("evm.db.password", "wallet123"));
        return dataSource;
    }
}
