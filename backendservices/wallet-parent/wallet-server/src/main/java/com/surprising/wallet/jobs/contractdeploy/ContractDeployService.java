package com.surprising.wallet.jobs.contractdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.common.QrCodeUtil;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.jobs.contractdeploy.ContractTemplateRegistry.CompiledTemplate;
import com.surprising.wallet.jobs.contractdeploy.ContractTemplateRegistry.TemplateType;
import com.surprising.wallet.jobs.walletapp.WalletAuthService.WalletUser;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ContractDeployService {
    private static final int DEFAULT_BIZ = 0;
    private static final String WALLET_ROLE_CONTRACT_DEPLOYER = "CONTRACT_DEPLOYER";
    private static final long CONTRACT_DEPLOYER_INDEX_BASE = 1_000_000L;
    private static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ERC20_FALLBACK_GAS_LIMIT = BigInteger.valueOf(3_200_000L);
    private static final BigInteger ERC721_FALLBACK_GAS_LIMIT = BigInteger.valueOf(3_700_000L);
    private static final BigInteger ESTIMATE_GAS_CAP = BigInteger.valueOf(8_000_000L);
    private static final BigDecimal WEI_PER_NATIVE = new BigDecimal("1000000000000000000");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{Alnum}][\\p{Alnum} ._\\-]{0,63}$");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_\\-]{0,15}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ChainJdbcRepository repository;
    private final HotWalletAddressService hotWalletAddressService;
    private final AccountSecp256k1KeyService keyService;
    private final ChainRpcNodeService rpcNodeService;
    private final ContractTemplateRegistry templateRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractDeployService(JdbcTemplate jdbcTemplate,
                                 ChainJdbcRepository repository,
                                 HotWalletAddressService hotWalletAddressService,
                                 AccountSecp256k1KeyService keyService,
                                 ChainRpcNodeService rpcNodeService,
                                 ContractTemplateRegistry templateRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.hotWalletAddressService = hotWalletAddressService;
        this.keyService = keyService;
        this.rpcNodeService = rpcNodeService;
        this.templateRegistry = templateRegistry;
    }

    public Map<String, Object> templates() {
        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("templates", templateRegistry.templateSummaries());
        payload.put("chains", evmChains());
        payload.put("walletRole", WALLET_ROLE_CONTRACT_DEPLOYER);
        payload.put("notes", List.of(
                "Deployment addresses are separated from normal deposit addresses.",
                "Users fund their own deployment gas; hot wallets do not top up gas.",
                "Deployed ERC20/ERC721 contracts are not added to the wallet token list automatically."));
        return payload;
    }

    public Map<String, Object> deployerAddress(WalletUser user, DeployerAddressRequest request) {
        AccountChainProfile profile = requireEvmProfile(normalizeChain(request.chain()));
        ChainAddressRecord record = Boolean.TRUE.equals(request.forceNew())
                ? createNewDeployerAddress(user.id(), profile)
                : latestOrCreateDeployerAddress(user.id(), profile);
        return deployerPayload(profile, record, null);
    }

    public Map<String, Object> preview(WalletUser user, ContractDeployRequest request) {
        DeploymentPlan plan = deploymentPlan(user, request);
        FeeQuote fee = quoteDeployment(plan.profile(), plan.deployer(), plan.deployData(), plan.template().type());
        return previewPayload(plan, fee, "PREVIEW");
    }

    public Map<String, Object> deploy(WalletUser user, ContractDeployRequest request) {
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deployment confirmation is required");
        }
        DeploymentPlan plan = deploymentPlan(user, request);
        FeeQuote fee = quoteDeployment(plan.profile(), plan.deployer(), plan.deployData(), plan.template().type());
        ensureEnoughFunds(plan, fee);

        String orderNo = newOrderNo();
        insertOrder(orderNo, user.id(), plan, fee, "SIGNING", null, null, null);
        if (!repository.freezeLedgerBalance(plan.profile().getChain(), plan.profile().getNativeSymbol(),
                plan.deployer().getAccountId(), fee.feeLimitNative())) {
            updateOrderError(plan.profile().getChain(), orderNo, "WAITING_FOR_FUNDS", "insufficient available ledger balance");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available ledger balance");
        }

        try {
            BroadcastResult sent = broadcastDeployment(plan, fee);
            updateOrderSent(plan.profile().getChain(), orderNo, sent.txHash(), sent.nonce());
            Map<String, Object> payload = previewPayload(plan, fee, "SENT");
            payload.put("orderNo", orderNo);
            payload.put("txHash", sent.txHash());
            payload.put("nonce", sent.nonce());
            payload.put("message", "deployment transaction was broadcast; refresh orders to confirm contract address");
            return payload;
        } catch (RuntimeException e) {
            repository.releaseLockedBalance(plan.profile().getChain(), plan.profile().getNativeSymbol(),
                    plan.deployer().getAccountId(), fee.feeLimitNative());
            updateOrderError(plan.profile().getChain(), orderNo, "FAILED", e.getMessage());
            throw e;
        }
    }

    public List<Map<String, Object>> orders(WalletUser user, int limit) {
        refreshPendingOrders(user.id());
        int rowLimit = Math.max(1, Math.min(limit, 50));
        return jdbcTemplate.queryForList("""
                select order_no as "orderNo", chain, network, template_type as "templateType",
                       contract_name as "contractName", contract_symbol as "contractSymbol",
                       deployer_address as "deployerAddress", owner_address as "ownerAddress",
                       native_symbol as "nativeSymbol", status, tx_hash as "txHash",
                       contract_address as "contractAddress", gas_limit as "gasLimit",
                       gas_price_wei as "gasPriceWei", fee_limit as "feeLimit",
                       fee_actual as "feeActual", error_message as "errorMessage",
                       created_at as "createdAt", updated_at as "updatedAt"
                  from contract_deployment_order
                 where user_id = ?
                 order by id desc
                 limit ?
                """, user.id(), rowLimit);
    }

    private DeploymentPlan deploymentPlan(WalletUser user, ContractDeployRequest request) {
        TemplateType type = parseType(request.templateType());
        AccountChainProfile profile = requireEvmProfile(normalizeChain(request.chain()));
        ChainAddressRecord deployer = latestOrCreateDeployerAddress(user.id(), profile);
        String ownerAddress = normalizeOwner(request.ownerAddress(), deployer.getAddress());
        CompiledTemplate template = templateRegistry.require(type);
        ContractParameters parameters = type == TemplateType.ERC20
                ? erc20Parameters(request, ownerAddress)
                : erc721Parameters(request, ownerAddress);
        String constructorData = constructorData(type, parameters);
        String deployData = "0x" + strip0x(template.bytecode()) + strip0x(constructorData);
        return new DeploymentPlan(user, profile, deployer, template, parameters, constructorData, deployData);
    }

    private Map<String, Object> previewPayload(DeploymentPlan plan, FeeQuote fee, String status) {
        Map<String, Object> payload = orderedMap();
        payload.put("status", status);
        payload.put("chain", plan.profile().getChain());
        payload.put("network", plan.profile().getNetwork());
        payload.put("nativeSymbol", plan.profile().getNativeSymbol());
        payload.put("templateType", plan.template().type().name());
        payload.put("contractName", plan.parameters().name());
        payload.put("contractSymbol", plan.parameters().symbol());
        payload.put("deployer", deployerPayload(plan.profile(), plan.deployer(), fee));
        payload.put("ownerAddress", plan.parameters().ownerAddress());
        payload.put("constructorArgs", plan.parameters().constructorView());
        payload.put("sourceCode", plan.template().source());
        payload.put("abi", plan.template().abiJson());
        payload.put("compilerVersion", plan.template().compilerVersion());
        payload.put("bytecodeHash", plan.template().bytecodeHash());
        payload.put("gas", fee.toPayload());
        payload.put("securityNotes", securityNotes(plan.template().type()));
        payload.put("warnings", deploymentWarnings(plan));
        payload.put("readyToDeploy", fee.feeLimitNative().compareTo(ledgerAvailable(
                plan.profile().getChain(), plan.profile().getNativeSymbol(), plan.deployer().getAccountId())) <= 0);
        return payload;
    }

    private Map<String, Object> deployerPayload(AccountChainProfile profile, ChainAddressRecord record, FeeQuote fee) {
        Map<String, Object> payload = orderedMap();
        payload.put("chain", profile.getChain());
        payload.put("network", profile.getNetwork());
        payload.put("nativeSymbol", profile.getNativeSymbol());
        payload.put("walletRole", record.getWalletRole());
        payload.put("address", record.getAddress());
        payload.put("qrCodeDataUrl", "data:image/png;base64," + QrCodeUtil.base64(record.getAddress()));
        payload.put("accountId", record.getAccountId());
        payload.put("addressIndex", record.getAddressIndex());
        payload.put("derivationPath", record.getDerivationPath());
        payload.put("availableBalance", ledgerAvailable(profile.getChain(), profile.getNativeSymbol(),
                record.getAccountId()).stripTrailingZeros().toPlainString());
        BigDecimal chainBalance = chainBalance(profile, record.getAddress()).orElse(null);
        payload.put("chainBalance", chainBalance == null ? null : chainBalance.stripTrailingZeros().toPlainString());
        if (fee != null) {
            payload.put("estimatedFee", fee.feeLimitNative().stripTrailingZeros().toPlainString());
            payload.put("feeReady", ledgerAvailable(profile.getChain(), profile.getNativeSymbol(), record.getAccountId())
                    .compareTo(fee.feeLimitNative()) >= 0
                    && (chainBalance == null || chainBalance.compareTo(fee.feeLimitNative()) >= 0));
        }
        payload.put("warnings", List.of(
                "Only deposit " + profile.getNativeSymbol() + " on " + profile.getChain()
                        + " to this contract deployment address.",
                "This address is for contract deployment gas and is separated from normal deposit addresses.",
                "Funds in this role are not collected by the hot-wallet collection job."));
        return payload;
    }

    private List<Map<String, Object>> evmChains() {
        return jdbcTemplate.queryForList("""
                select chain, network, native_symbol as "nativeSymbol", chain_id as "chainId",
                       explorer_url as "explorerUrl", withdraw_confirmations as "confirmations"
                  from chain_profile
                 where enabled = true
                   and lower(family) = 'evm'
                 order by chain
                """);
    }

    private ChainAddressRecord latestOrCreateDeployerAddress(long userId, AccountChainProfile profile) {
        ChainAddressRecord existing = latestDeployerAddress(userId, profile).orElse(null);
        return existing == null ? createNewDeployerAddress(userId, profile) : existing;
    }

    private Optional<ChainAddressRecord> latestDeployerAddress(long userId, AccountChainProfile profile) {
        List<ChainAddressRecord> rows = jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                          from chain_address
                         where chain = ? and asset_symbol = ? and user_id = ? and biz = ?
                           and wallet_role = ? and enabled = true
                         order by address_index desc, id desc
                         limit 1
                        """,
                (rs, rowNum) -> ChainAddressRecord.builder()
                        .id(rs.getLong("id"))
                        .chain(rs.getString("chain"))
                        .assetSymbol(rs.getString("asset_symbol"))
                        .accountId(rs.getString("account_id"))
                        .userId(rs.getLong("user_id"))
                        .biz(rs.getInt("biz"))
                        .addressIndex(rs.getLong("address_index"))
                        .address(rs.getString("address"))
                        .ownerAddress(rs.getString("owner_address"))
                        .derivationPath(rs.getString("derivation_path"))
                        .walletRole(rs.getString("wallet_role"))
                        .enabled(rs.getBoolean("enabled"))
                        .build(),
                profile.getChain(), profile.getNativeSymbol(), userId, DEFAULT_BIZ, WALLET_ROLE_CONTRACT_DEPLOYER);
        return rows.stream().findFirst();
    }

    private ChainAddressRecord createNewDeployerAddress(long userId, AccountChainProfile profile) {
        long index = nextDeployerIndex(profile, userId);
        ChainAddressRecord record = hotWalletAddressService.deriveAddress(
                profile, userId, DEFAULT_BIZ, index, WALLET_ROLE_CONTRACT_DEPLOYER);
        repository.upsertChainAddress(record);
        return repository.findChainAddress(profile.getChain(), profile.getNativeSymbol(), userId, DEFAULT_BIZ,
                        index, WALLET_ROLE_CONTRACT_DEPLOYER)
                .orElseThrow(() -> new IllegalStateException("contract deployer address was not persisted"));
    }

    private long nextDeployerIndex(AccountChainProfile profile, long userId) {
        Long max = jdbcTemplate.queryForObject("""
                        select max(address_index)
                          from chain_address
                         where chain = ?
                           and asset_symbol = ?
                           and user_id = ?
                           and biz = ?
                           and address_index >= ?
                        """,
                Long.class, profile.getChain(), profile.getNativeSymbol(), userId, DEFAULT_BIZ,
                CONTRACT_DEPLOYER_INDEX_BASE);
        return Math.max(CONTRACT_DEPLOYER_INDEX_BASE - 1L, max == null ? 0L : max) + 1L;
    }

    private FeeQuote quoteDeployment(AccountChainProfile profile, ChainAddressRecord deployer,
                                     String deployData, TemplateType type) {
        BigInteger fallback = type == TemplateType.ERC20 ? ERC20_FALLBACK_GAS_LIMIT : ERC721_FALLBACK_GAS_LIMIT;
        try {
            return withWeb3(profile, web3j -> {
                BigInteger gasPriceWei = web3j.ethGasPrice().send().getGasPrice();
                BigInteger nonce = web3j.ethGetTransactionCount(deployer.getAddress(),
                                DefaultBlockParameterName.PENDING)
                        .send()
                        .getTransactionCount();
                Transaction estimateTx = Transaction.createContractTransaction(
                        deployer.getAddress(), nonce, gasPriceWei, ESTIMATE_GAS_CAP, ZERO, deployData);
                BigInteger estimate = web3j.ethEstimateGas(estimateTx).send().getAmountUsed();
                if (estimate == null || estimate.signum() <= 0) {
                    estimate = fallback;
                }
                BigInteger gasLimit = estimate.multiply(BigInteger.valueOf(125))
                        .divide(BigInteger.valueOf(100))
                        .max(fallback);
                return new FeeQuote(gasPriceWei, gasLimit, false, null);
            });
        } catch (RuntimeException e) {
            log.warn("EVM contract gas estimate fallback: chain={} error={}", profile.getChain(), e.getMessage());
            try {
                BigInteger gasPriceWei = withWeb3(profile, web3j -> web3j.ethGasPrice().send().getGasPrice());
                return new FeeQuote(gasPriceWei, fallback, true, e.getMessage());
            } catch (RuntimeException nested) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "unable to estimate deployment gas: " + nested.getMessage());
            }
        }
    }

    private BroadcastResult broadcastDeployment(DeploymentPlan plan, FeeQuote fee) {
        AccountChainProfile profile = plan.profile();
        return withWeb3(profile, web3j -> {
            BigInteger chainNonce = web3j.ethGetTransactionCount(plan.deployer().getAddress(),
                            DefaultBlockParameterName.PENDING)
                    .send()
                    .getTransactionCount();
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    profile.getChain(), normalizeAddress(plan.deployer().getAddress()), chainNonce.longValueExact()));
            RawTransaction tx = RawTransaction.createContractTransaction(
                    nonce, fee.gasPriceWei(), fee.gasLimit(), ZERO, plan.deployData());
            byte[] signed = TransactionEncoder.signMessage(tx, profile.getChainId(),
                    credentials(profile, plan.deployer()));
            EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                throw new IllegalStateException(sent.getError().getMessage());
            }
            return new BroadcastResult(sent.getTransactionHash(), nonce.longValueExact());
        });
    }

    private void ensureEnoughFunds(DeploymentPlan plan, FeeQuote fee) {
        BigDecimal ledger = ledgerAvailable(plan.profile().getChain(), plan.profile().getNativeSymbol(),
                plan.deployer().getAccountId());
        if (ledger.compareTo(fee.feeLimitNative()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "insufficient scanned deployment gas balance");
        }
        Optional<BigDecimal> chainBalance = chainBalance(plan.profile(), plan.deployer().getAddress());
        if (chainBalance.isPresent() && chainBalance.get().compareTo(fee.feeLimitNative()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "insufficient on-chain deployment gas balance");
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    protected void insertOrder(String orderNo, long userId, DeploymentPlan plan, FeeQuote fee,
                               String status, String txHash, String contractAddress, String error) {
        try {
            jdbcTemplate.update("""
                    insert into contract_deployment_order(
                        order_no, user_id, chain, network, template_type, contract_name, contract_symbol,
                        deployer_address, account_id, owner_address, native_symbol, status,
                        parameters_json, constructor_args_json, source_code, abi_json, bytecode_hash,
                        deploy_data_hash, gas_price_wei, gas_limit, fee_limit, fee_actual,
                        tx_hash, contract_address, error_message, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                            ?, ?, ?, now(), now())
                    """,
                    orderNo,
                    userId,
                    plan.profile().getChain(),
                    plan.profile().getNetwork(),
                    plan.template().type().name(),
                    plan.parameters().name(),
                    plan.parameters().symbol(),
                    plan.deployer().getAddress(),
                    plan.deployer().getAccountId(),
                    plan.parameters().ownerAddress(),
                    plan.profile().getNativeSymbol(),
                    status,
                    objectMapper.writeValueAsString(plan.parameters().parameterView()),
                    objectMapper.writeValueAsString(plan.parameters().constructorView()),
                    plan.template().source(),
                    plan.template().abiJson(),
                    plan.template().bytecodeHash(),
                    sha256Hex(plan.deployData()),
                    fee.gasPriceWei().toString(),
                    fee.gasLimit().longValueExact(),
                    fee.feeLimitNative(),
                    txHash,
                    contractAddress,
                    trim(error, 1000));
        } catch (Exception e) {
            throw new IllegalStateException("unable to create contract deployment order", e);
        }
    }

    private void updateOrderSent(String chain, String orderNo, String txHash, long nonce) {
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = 'SENT',
                       tx_hash = ?,
                       nonce = ?,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """, txHash, nonce, chain, orderNo);
    }

    private void updateOrderError(String chain, String orderNo, String status, String errorMessage) {
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = ?,
                       error_message = ?,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """, status, trim(errorMessage, 1000), chain, orderNo);
    }

    private void refreshPendingOrders(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, order_no, chain, tx_hash, account_id, native_symbol, fee_limit, gas_price_wei
                  from contract_deployment_order
                 where user_id = ?
                   and status = 'SENT'
                   and tx_hash is not null
                 order by id
                 limit 10
                """, userId);
        for (Map<String, Object> row : rows) {
            try {
                refreshPendingOrder(row);
            } catch (RuntimeException e) {
                log.warn("contract deployment confirm refresh failed: order={} error={}",
                        row.get("order_no"), e.getMessage());
            }
        }
    }

    private void refreshPendingOrder(Map<String, Object> row) {
        String chain = String.valueOf(row.get("chain"));
        String txHash = String.valueOf(row.get("tx_hash"));
        AccountChainProfile profile = requireEvmProfile(chain);
        Optional<TransactionReceipt> receipt = withWeb3(profile, web3j ->
                web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt());
        if (receipt.isEmpty() || receipt.get().getBlockNumber() == null) {
            return;
        }
        TransactionReceipt value = receipt.get();
        BigInteger latest = withWeb3(profile, web3j -> web3j.ethBlockNumber().send().getBlockNumber());
        BigInteger confirmations = latest.subtract(value.getBlockNumber()).add(BigInteger.ONE);
        int required = Math.max(1, profile.getWithdrawConfirmations());
        if (confirmations.compareTo(BigInteger.valueOf(required)) < 0) {
            return;
        }
        BigInteger gasPrice = new BigInteger(String.valueOf(row.get("gas_price_wei")));
        BigInteger gasUsed = value.getGasUsed() == null ? BigInteger.ZERO : value.getGasUsed();
        BigDecimal actualFee = weiToNative(gasPrice.multiply(gasUsed));
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        if (actualFee.signum() > 0) {
            repository.settleLockedDebit(chain, nativeSymbol, accountId, actualFee);
        }
        BigDecimal release = feeLimit.subtract(actualFee);
        if (release.signum() > 0) {
            repository.releaseLockedBalance(chain, nativeSymbol, accountId, release);
        }
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = ?,
                       contract_address = ?,
                       fee_actual = ?,
                       block_height = ?,
                       confirmations = ?,
                       error_message = ?,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """,
                value.isStatusOK() ? "CONFIRMED" : "FAILED",
                value.getContractAddress(),
                actualFee,
                value.getBlockNumber().longValueExact(),
                confirmations.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue(),
                value.isStatusOK() ? null : "contract deployment transaction failed",
                chain,
                orderNo);
    }

    private ContractParameters erc20Parameters(ContractDeployRequest request, String ownerAddress) {
        int decimals = request.decimals() == null ? 18 : request.decimals();
        if (decimals < 0 || decimals > 18) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ERC20 decimals must be between 0 and 18");
        }
        String name = normalizeName(request.name());
        String symbol = normalizeSymbol(request.symbol());
        BigInteger initialSupply = tokenAmount(request.initialSupply(), decimals, true, "initial supply");
        BigInteger maxSupply = tokenAmount(request.maxSupply(), decimals, false, "max supply");
        if (initialSupply.compareTo(maxSupply) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "initial supply must not exceed max supply");
        }
        boolean mintable = Boolean.TRUE.equals(request.mintable());
        Map<String, Object> params = orderedMap();
        params.put("name", name);
        params.put("symbol", symbol);
        params.put("decimals", decimals);
        params.put("initialSupply", request.initialSupply() == null ? "0" : request.initialSupply().trim());
        params.put("maxSupply", request.maxSupply().trim());
        params.put("mintable", mintable);
        params.put("ownerAddress", ownerAddress);
        List<Type> args = List.of(
                new Utf8String(name),
                new Utf8String(symbol),
                new Uint8(BigInteger.valueOf(decimals)),
                new Uint256(initialSupply),
                new Uint256(maxSupply),
                new Address(ownerAddress),
                new Bool(mintable));
        return new ContractParameters(name, symbol, ownerAddress, params, constructorView(args), args);
    }

    private ContractParameters erc721Parameters(ContractDeployRequest request, String ownerAddress) {
        String name = normalizeName(request.name());
        String symbol = normalizeSymbol(request.symbol());
        BigInteger maxSupply = integerAmount(request.maxSupply(), "collection max supply");
        String baseUri = trim(request.baseUri(), 300);
        Map<String, Object> params = orderedMap();
        params.put("name", name);
        params.put("symbol", symbol);
        params.put("baseUri", baseUri);
        params.put("maxSupply", maxSupply.toString());
        params.put("ownerAddress", ownerAddress);
        List<Type> args = List.of(
                new Utf8String(name),
                new Utf8String(symbol),
                new Utf8String(baseUri),
                new Uint256(maxSupply),
                new Address(ownerAddress));
        return new ContractParameters(name, symbol, ownerAddress, params, constructorView(args), args);
    }

    private String constructorData(TemplateType type, ContractParameters parameters) {
        try {
            return FunctionEncoder.encodeConstructor(parameters.arguments());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid " + type.name() + " constructor arguments");
        }
    }

    private List<Map<String, Object>> constructorView(List<Type> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Type arg : args) {
            Map<String, Object> row = orderedMap();
            row.put("type", arg.getTypeAsString());
            row.put("value", String.valueOf(arg.getValue()));
            rows.add(row);
        }
        return rows;
    }

    private TemplateType parseType(String value) {
        try {
            return TemplateType.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private AccountChainProfile requireEvmProfile(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(chain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "chain profile is not enabled: " + chain));
        ChainType chainType;
        try {
            chainType = ChainType.valueOf(profile.getChain());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported chain: " + chain);
        }
        if (!chainType.isEvm() || !"evm".equalsIgnoreCase(profile.getFamily())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contract deployment currently supports EVM chains only");
        }
        if (profile.getChainId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EVM chain id is not configured");
        }
        return profile;
    }

    private BigDecimal ledgerAvailable(String chain, String symbol, String accountId) {
        List<BigDecimal> rows = jdbcTemplate.queryForList("""
                select available_balance
                  from ledger_balance
                 where chain = ?
                   and asset_symbol = ?
                   and lower(account_id) = lower(?)
                 limit 1
                """, BigDecimal.class, chain, symbol, accountId);
        return rows.isEmpty() || rows.get(0) == null ? BigDecimal.ZERO : rows.get(0);
    }

    private Optional<BigDecimal> chainBalance(AccountChainProfile profile, String address) {
        try {
            return Optional.of(withWeb3(profile, web3j -> weiToNative(web3j.ethGetBalance(
                    address, DefaultBlockParameterName.LATEST).send().getBalance())));
        } catch (RuntimeException e) {
            log.warn("unable to read contract deployer chain balance: chain={} address={} error={}",
                    profile.getChain(), address, e.getMessage());
            return Optional.empty();
        }
    }

    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }

    private <T> T withWeb3(AccountChainProfile profile, Web3Request<T> request) {
        return rpcNodeService.withFailover(profile.getChain(), profile.getNetwork(), node -> {
            Web3j web3j = Web3j.build(new HttpService(node.getRpcUrl()));
            try {
                return request.apply(web3j);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                web3j.shutdown();
            }
        });
    }

    private static List<String> securityNotes(TemplateType type) {
        if (type == TemplateType.ERC20) {
            return List.of(
                    "ERC20 uses a fixed OpenZeppelin-based template with Ownable, Capped, Pausable, Burnable and Permit.",
                    "Max supply is enforced in the contract; owner mint can be disabled permanently at deployment.",
                    "Owner can pause transfers for emergency handling.");
        }
        return List.of(
                "ERC721 uses a fixed OpenZeppelin-based template with Ownable, Enumerable, URI storage, Pausable and Burnable.",
                "Max supply is immutable and enforced by the mint function.",
                "Owner can pause transfers for emergency handling.");
    }

    private static List<String> deploymentWarnings(DeploymentPlan plan) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Deployment spends " + plan.profile().getNativeSymbol()
                + " from the contract deployment address; make sure the address has scanned gas balance.");
        warnings.add("The deployed contract will not be added to wallet token configuration automatically.");
        warnings.add("Only ERC20 and ERC721 fixed templates are supported in this version.");
        if (plan.template().type() == TemplateType.ERC20
                && Boolean.FALSE.equals(plan.parameters().parameterView().get("mintable"))) {
            warnings.add("Owner mint is disabled permanently for this ERC20 deployment.");
        }
        return warnings;
    }

    private static String normalizeChain(String chain) {
        String value = chain == null ? "" : chain.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid chain is required");
        }
        return value;
    }

    private static String normalizeName(String name) {
        String value = trim(name, 64);
        if (!NAME_PATTERN.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contract name must be 1-64 letters, numbers, spaces or ._-");
        }
        return value;
    }

    private static String normalizeSymbol(String symbol) {
        String value = trim(symbol, 16).toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "symbol must be 1-16 uppercase letters, numbers, _ or -");
        }
        return value;
    }

    private static String normalizeOwner(String requestedOwner, String defaultOwner) {
        String value = requestedOwner == null || requestedOwner.isBlank() ? defaultOwner : requestedOwner.trim();
        if (!value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid EVM address");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static BigInteger tokenAmount(String amount, int decimals, boolean allowZero, String label) {
        String value = amount == null ? "" : amount.trim();
        if (value.isBlank() && allowZero) {
            return BigInteger.ZERO;
        }
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        try {
            BigInteger raw = new BigDecimal(value).movePointRight(decimals).toBigIntegerExact();
            requireUint256(raw, allowZero, label);
            return raw;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " must be a non-negative decimal with at most " + decimals + " decimal places");
        }
    }

    private static BigInteger integerAmount(String amount, String label) {
        String value = amount == null ? "" : amount.trim();
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        try {
            BigInteger raw = new BigInteger(value);
            requireUint256(raw, false, label);
            return raw;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a positive integer");
        }
    }

    private static void requireUint256(BigInteger value, boolean allowZero, String label) {
        if (value.signum() < 0 || (!allowZero && value.signum() == 0) || value.compareTo(UINT256_MAX) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " must be within uint256 range");
        }
    }

    private static BigDecimal weiToNative(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_NATIVE, 18, RoundingMode.DOWN);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String strip0x(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private static String newOrderNo() {
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);
        return "CD" + System.currentTimeMillis() + HexFormat.of().formatHex(random).toUpperCase(Locale.ROOT);
    }

    private static String normalizeAddress(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    @FunctionalInterface
    private interface Web3Request<T> {
        T apply(Web3j web3j) throws Exception;
    }

    public record DeployerAddressRequest(String chain, Boolean forceNew) {
    }

    public record ContractDeployRequest(String chain,
                                        String templateType,
                                        String name,
                                        String symbol,
                                        Integer decimals,
                                        String initialSupply,
                                        String maxSupply,
                                        Boolean mintable,
                                        String baseUri,
                                        String ownerAddress,
                                        Boolean confirmed) {
    }

    private record DeploymentPlan(WalletUser user,
                                  AccountChainProfile profile,
                                  ChainAddressRecord deployer,
                                  CompiledTemplate template,
                                  ContractParameters parameters,
                                  String constructorData,
                                  String deployData) {
    }

    private record ContractParameters(String name,
                                      String symbol,
                                      String ownerAddress,
                                      Map<String, Object> parameterView,
                                      List<Map<String, Object>> constructorView,
                                      List<Type> arguments) {
    }

    private record FeeQuote(BigInteger gasPriceWei,
                            BigInteger gasLimit,
                            boolean fallback,
                            String fallbackReason) {
        private BigDecimal feeLimitNative() {
            return weiToNative(gasPriceWei.multiply(gasLimit));
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = orderedMap();
            payload.put("gasPriceWei", gasPriceWei.toString());
            payload.put("gasLimit", gasLimit.toString());
            payload.put("feeLimit", feeLimitNative().stripTrailingZeros().toPlainString());
            payload.put("fallback", fallback);
            payload.put("fallbackReason", fallbackReason);
            return payload;
        }
    }

    private record BroadcastResult(String txHash, long nonce) {
    }
}
