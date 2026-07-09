package com.surprising.wallet.jobs.contractdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.common.QrCodeUtil;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.jobs.contractdeploy.ContractTemplateRegistry.CompiledTemplate;
import com.surprising.wallet.jobs.contractdeploy.ContractTemplateRegistry.ContractFamily;
import com.surprising.wallet.jobs.contractdeploy.ContractTemplateRegistry.TemplateType;
import com.surprising.wallet.jobs.contractdeploy.AptosMoveCompiler.CompiledAptosPackage;
import com.surprising.wallet.jobs.contractdeploy.AptosMoveCompiler.RenderedAptosPackage;
import com.surprising.wallet.jobs.contractdeploy.SuiMoveCompiler.CompiledMovePackage;
import com.surprising.wallet.jobs.contractdeploy.SuiMoveCompiler.RenderedMovePackage;
import com.surprising.wallet.service.chain.aptos.AptosRpcClient;
import com.surprising.wallet.service.chain.aptos.AptosTransactionService;
import com.surprising.wallet.service.chain.near.NearKeyService;
import com.surprising.wallet.service.chain.near.NearRpcClient;
import com.surprising.wallet.service.chain.near.NearTransactionService;
import com.surprising.wallet.service.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.service.chain.polkadot.PolkadotRuntimeClient;
import com.surprising.wallet.service.chain.polkadot.PolkadotTransactionService;
import com.surprising.wallet.service.chain.solana.SolanaRpcClient;
import com.surprising.wallet.service.chain.solana.SolanaTransactionService;
import com.surprising.wallet.service.chain.sui.SuiRpcClient;
import com.surprising.wallet.service.chain.sui.SuiTransactionService;
import com.surprising.wallet.service.chain.ton.TonCenterClient;
import com.surprising.wallet.service.chain.ton.TonTransactionService;
import com.surprising.wallet.service.chain.tron.TronAddressCodec;
import com.surprising.wallet.service.chain.tron.TronClientFactory;
import com.surprising.wallet.service.chain.tron.TronTridentClient;
import com.surprising.wallet.service.chain.tron.TronTridentKeyFactory;
import com.surprising.wallet.service.chain.tron.TronTransactionService;
import com.surprising.wallet.jobs.walletapp.WalletAuthService.WalletUser;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.p2p.solanaj.core.PublicKey;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.smartcontract.token.ft.JettonMinter;
import org.ton.ton4j.smartcontract.token.nft.NftCollection;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.core.utils.Utils;
import org.tron.trident.proto.Response;
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
import java.nio.charset.StandardCharsets;
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
    private static final BigDecimal SUN_PER_TRX = new BigDecimal("1000000");
    private static final long TRON_ERC20_FEE_LIMIT_SUN = 500_000_000L;
    private static final long TRON_ERC721_FEE_LIMIT_SUN = 700_000_000L;
    private static final long TRON_CONSUME_USER_RESOURCE_PERCENT = 100L;
    private static final long TRON_ORIGIN_ENERGY_LIMIT = 10_000_000L;
    private static final BigDecimal YOCTO_PER_NEAR = new BigDecimal("1000000000000000000000000");
    private static final BigInteger NEAR_STORAGE_BYTE_COST_YOCTO = new BigInteger("10000000000000000000");
    private static final long NEAR_CONTRACT_DEPLOY_GAS = 200_000_000_000_000L;
    private static final long NEAR_EXTRA_STORAGE_BYTES = 50_000L;
    private static final BigDecimal LAMPORTS_PER_SOL = new BigDecimal("1000000000");
    private static final int SOLANA_SPL_MINT_ACCOUNT_LENGTH = 82;
    private static final int SOLANA_SPL_TOKEN_ACCOUNT_LENGTH = 165;
    private static final long SOLANA_DEPLOY_FEE_SIGNATURES = 3L;
    private static final BigDecimal OCTAS_PER_APT = new BigDecimal("100000000");
    private static final long APTOS_DEPLOY_MIN_GAS_AMOUNT = 200_000L;
    private static final BigInteger APTOS_U64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigDecimal MIST_PER_SUI = new BigDecimal("1000000000");
    private static final long SUI_CONTRACT_DEPLOY_GAS_BUDGET_MIST = 1_000_000_000L;
    private static final BigInteger SUI_U64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger POLKADOT_ASSET_ID_OFFSET = BigInteger.valueOf(100_000_000L);
    private static final BigInteger POLKADOT_ASSET_ID_MOD = BigInteger.valueOf(1_900_000_000L);
    private static final BigInteger POLKADOT_ASSET_HUB_MIN_BALANCE = BigInteger.ONE;
    private static final BigDecimal NANO_PER_TON = new BigDecimal("1000000000");
    private static final BigInteger TON_DEPLOY_FALLBACK_RESERVE_NANO = BigInteger.valueOf(500_000_000L);
    private static final BigInteger TON_JETTON_WALLET_DEPLOY_NANO = BigInteger.valueOf(80_000_000L);
    private static final BigInteger TON_JETTON_FORWARD_NANO = BigInteger.ZERO;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{Alnum}][\\p{Alnum} ._\\-]{0,63}$");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_\\-]{0,15}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ChainJdbcRepository repository;
    private final HotWalletAddressService hotWalletAddressService;
    private final AccountSecp256k1KeyService keyService;
    private final ChainRpcNodeService rpcNodeService;
    private final ContractTemplateRegistry templateRegistry;
    private final TronClientFactory tronClientFactory;
    private final NearRpcClient nearRpcClient;
    private final NearTransactionService nearTransactionService;
    private final SolanaRpcClient solanaRpcClient;
    private final SolanaTransactionService solanaTransactionService;
    private final AptosRpcClient aptosRpcClient;
    private final AptosTransactionService aptosTransactionService;
    private final AptosMoveCompiler aptosMoveCompiler;
    private final SuiRpcClient suiRpcClient;
    private final SuiTransactionService suiTransactionService;
    private final SuiMoveCompiler suiMoveCompiler;
    private final PolkadotRuntimeClient polkadotRuntimeClient;
    private final PolkadotTransactionService polkadotTransactionService;
    private final TonCenterClient tonCenterClient;
    private final TonTransactionService tonTransactionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractDeployService(JdbcTemplate jdbcTemplate,
                                 ChainJdbcRepository repository,
                                 HotWalletAddressService hotWalletAddressService,
                                 AccountSecp256k1KeyService keyService,
                                 ChainRpcNodeService rpcNodeService,
                                 ContractTemplateRegistry templateRegistry,
                                 TronClientFactory tronClientFactory,
                                 NearRpcClient nearRpcClient,
                                 NearTransactionService nearTransactionService,
                                 SolanaRpcClient solanaRpcClient,
                                 SolanaTransactionService solanaTransactionService,
                                 AptosRpcClient aptosRpcClient,
                                 AptosTransactionService aptosTransactionService,
                                 AptosMoveCompiler aptosMoveCompiler,
                                 SuiRpcClient suiRpcClient,
                                 SuiTransactionService suiTransactionService,
                                 SuiMoveCompiler suiMoveCompiler,
                                 PolkadotRuntimeClient polkadotRuntimeClient,
                                 PolkadotTransactionService polkadotTransactionService,
                                 TonCenterClient tonCenterClient,
                                 TonTransactionService tonTransactionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.hotWalletAddressService = hotWalletAddressService;
        this.keyService = keyService;
        this.rpcNodeService = rpcNodeService;
        this.templateRegistry = templateRegistry;
        this.tronClientFactory = tronClientFactory;
        this.nearRpcClient = nearRpcClient;
        this.nearTransactionService = nearTransactionService;
        this.solanaRpcClient = solanaRpcClient;
        this.solanaTransactionService = solanaTransactionService;
        this.aptosRpcClient = aptosRpcClient;
        this.aptosTransactionService = aptosTransactionService;
        this.aptosMoveCompiler = aptosMoveCompiler;
        this.suiRpcClient = suiRpcClient;
        this.suiTransactionService = suiTransactionService;
        this.suiMoveCompiler = suiMoveCompiler;
        this.polkadotRuntimeClient = polkadotRuntimeClient;
        this.polkadotTransactionService = polkadotTransactionService;
        this.tonCenterClient = tonCenterClient;
        this.tonTransactionService = tonTransactionService;
    }

    public Map<String, Object> templates() {
        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("templates", templateRegistry.templateSummaries());
        payload.put("chains", contractChains());
        payload.put("walletRole", WALLET_ROLE_CONTRACT_DEPLOYER);
        payload.put("notes", List.of(
                "Deployment addresses are separated from normal deposit addresses.",
                "Users fund their own deployment gas; hot wallets do not top up gas.",
                "EVM supports ERC20/ERC721 templates; TRON supports TRC20/TRC721 templates; NEAR supports NEP-141/NEP-171 templates; Solana supports SPL Token/NFT mint templates; Aptos supports Coin/single-asset Move templates; Sui supports Coin/NFT Move templates; Polkadot supports Asset Hub asset templates; TON supports Jetton/NFT collection templates.",
                "Deployed contracts are not added to the wallet token list automatically."));
        return payload;
    }

    public Map<String, Object> deployerAddress(WalletUser user, DeployerAddressRequest request) {
        AccountChainProfile profile = requireContractProfile(normalizeChain(request.chain()));
        ChainAddressRecord record = Boolean.TRUE.equals(request.forceNew())
                ? createNewDeployerAddress(user.id(), profile)
                : latestOrCreateDeployerAddress(user.id(), profile);
        return deployerPayload(profile, record, null);
    }

    public Map<String, Object> preview(WalletUser user, ContractDeployRequest request) {
        DeploymentPlan plan = deploymentPlan(user, request);
        FeeQuote fee = quoteDeployment(plan);
        return previewPayload(plan, fee, "PREVIEW");
    }

    public Map<String, Object> deploy(WalletUser user, ContractDeployRequest request) {
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deployment confirmation is required");
        }
        DeploymentPlan plan = deploymentPlan(user, request);
        FeeQuote fee = quoteDeployment(plan);
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
            updateOrderSent(plan.profile().getChain(), orderNo, sent.txHash(), sent.nonce(), sent.contractAddress());
            Map<String, Object> payload = previewPayload(plan, fee, "SENT");
            payload.put("orderNo", orderNo);
            payload.put("txHash", sent.txHash());
            payload.put("nonce", sent.nonce());
            payload.put("contractAddress", sent.contractAddress());
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
        AccountChainProfile profile = requireContractProfile(normalizeChain(request.chain()));
        ContractFamily family = contractFamily(profile);
        ChainAddressRecord deployer = latestOrCreateDeployerAddress(user.id(), profile);
        String ownerAddress = normalizeOwner(profile, request.ownerAddress(), deployer.getAddress());
        if (family == ContractFamily.APTOS
                && !aptosNormalizeAddress(ownerAddress).equals(aptosNormalizeAddress(deployer.getAddress()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aptos contract owner must be the deployment address in this version");
        }
        CompiledTemplate template = templateRegistry.require(family, type);
        if (family == ContractFamily.POLKADOT
                && !ownerAddress.equals(deployer.getAddress())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Polkadot Asset Hub asset owner must be the deployment address in this version");
        }
        if (family == ContractFamily.TON
                && !tonAddressKey(ownerAddress).equals(tonAddressKey(deployer.getAddress()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TON contract owner must be the deployment address in this version");
        }
        ContractParameters parameters = type == TemplateType.ERC20
                ? erc20Parameters(family, profile, deployer, request, ownerAddress)
                : erc721Parameters(family, profile, deployer, request, ownerAddress);
        String constructorData = constructorData(family, type, parameters);
        RenderedAptosPackage aptosMovePackage = null;
        CompiledAptosPackage aptosCompiledPackage = null;
        RenderedMovePackage suiMovePackage = null;
        CompiledMovePackage suiCompiledPackage = null;
        String sourceCode = template.source();
        String bytecodeHash = template.bytecodeHash();
        if (family == ContractFamily.APTOS) {
            aptosMovePackage = renderAptosMovePackage(template, type, parameters, deployer);
            aptosCompiledPackage = aptosMoveCompiler.compile(aptosMovePackage);
            sourceCode = aptosMovePackage.source();
            bytecodeHash = aptosCompiledPackage.bytecodeHash();
        }
        if (family == ContractFamily.SUI) {
            suiMovePackage = renderSuiMovePackage(template, type, parameters);
            suiCompiledPackage = suiMoveCompiler.compile(suiMovePackage);
            sourceCode = suiMovePackage.source();
            bytecodeHash = suiCompiledPackage.bytecodeHash();
        }
        String deployData = deployData(family, template, constructorData, bytecodeHash);
        return new DeploymentPlan(user, profile, deployer, template, parameters, constructorData, deployData,
                sourceCode, bytecodeHash, aptosMovePackage, aptosCompiledPackage, suiMovePackage, suiCompiledPackage);
    }

    private String deployData(ContractFamily family, CompiledTemplate template,
                              String constructorData, String bytecodeHash) {
        if (family == ContractFamily.SUI) {
            return "sui:" + bytecodeHash + ":" + sha256Hex(constructorData);
        }
        if (family == ContractFamily.SOLANA) {
            return "solana:" + template.bytecodeHash() + ":" + sha256Hex(constructorData);
        }
        if (family == ContractFamily.APTOS) {
            return "aptos:" + bytecodeHash + ":" + sha256Hex(constructorData);
        }
        if (family == ContractFamily.POLKADOT) {
            return "polkadot:" + template.bytecodeHash() + ":" + sha256Hex(constructorData);
        }
        if (family == ContractFamily.TON) {
            return "ton:" + template.bytecodeHash() + ":" + sha256Hex(constructorData);
        }
        if (family == ContractFamily.NEAR) {
            return "near:" + template.bytecodeHash() + ":" + sha256Hex(constructorData);
        }
        return "0x" + strip0x(template.bytecode()) + strip0x(constructorData);
    }

    private Map<String, Object> previewPayload(DeploymentPlan plan, FeeQuote fee, String status) {
        Map<String, Object> payload = orderedMap();
        payload.put("status", status);
        payload.put("chain", plan.profile().getChain());
        payload.put("network", plan.profile().getNetwork());
        payload.put("family", plan.template().family().apiValue());
        payload.put("nativeSymbol", plan.profile().getNativeSymbol());
        payload.put("templateType", plan.template().type().name());
        payload.put("contractName", plan.parameters().name());
        payload.put("contractSymbol", plan.parameters().symbol());
        payload.put("deployer", deployerPayload(plan.profile(), plan.deployer(), fee));
        payload.put("ownerAddress", plan.parameters().ownerAddress());
        payload.put("constructorArgs", plan.parameters().constructorView());
        payload.put("sourceCode", plan.sourceCode());
        payload.put("abi", plan.template().abiJson());
        payload.put("compilerVersion", plan.template().compilerVersion());
        payload.put("evmVersion", plan.template().evmVersion());
        payload.put("bytecodeHash", plan.bytecodeHash());
        payload.put("gas", fee.toPayload());
        payload.put("securityNotes", securityNotes(plan.template()));
        payload.put("warnings", deploymentWarnings(plan));
        payload.put("readyToDeploy", fee.feeLimitNative().compareTo(ledgerAvailable(
                plan.profile().getChain(), plan.profile().getNativeSymbol(), plan.deployer().getAccountId())) <= 0);
        return payload;
    }

    private Map<String, Object> deployerPayload(AccountChainProfile profile, ChainAddressRecord record, FeeQuote fee) {
        Map<String, Object> payload = orderedMap();
        payload.put("chain", profile.getChain());
        payload.put("network", profile.getNetwork());
        payload.put("family", profile.getFamily());
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

    private List<Map<String, Object>> contractChains() {
        return jdbcTemplate.queryForList("""
                select chain, network, family, native_symbol as "nativeSymbol", chain_id as "chainId",
                       explorer_url as "explorerUrl", withdraw_confirmations as "confirmations"
                 from chain_profile
                 where enabled = true
                   and lower(family) in ('evm', 'tron', 'near', 'solana', 'aptos', 'sui', 'polkadot', 'ton')
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

    private FeeQuote quoteDeployment(DeploymentPlan plan) {
        AccountChainProfile profile = plan.profile();
        if (contractFamily(profile) == ContractFamily.SUI) {
            return suiFeeQuote();
        }
        if (contractFamily(profile) == ContractFamily.SOLANA) {
            return solanaFeeQuote(plan);
        }
        if (contractFamily(profile) == ContractFamily.APTOS) {
            return aptosFeeQuote(plan);
        }
        if (contractFamily(profile) == ContractFamily.POLKADOT) {
            return polkadotFeeQuote(plan);
        }
        if (contractFamily(profile) == ContractFamily.TON) {
            return tonFeeQuote(plan);
        }
        if (contractFamily(profile) == ContractFamily.NEAR) {
            return nearFeeQuote(plan);
        }
        if (contractFamily(profile) == ContractFamily.TRON) {
            TemplateType type = plan.template().type();
            long feeLimitSun = type == TemplateType.ERC20 ? TRON_ERC20_FEE_LIMIT_SUN : TRON_ERC721_FEE_LIMIT_SUN;
            return new FeeQuote(BigInteger.ONE, BigInteger.valueOf(feeLimitSun), true,
                    "TRON contract deployment uses a fixed fee_limit because full-node energy estimate is not reliable for create-contract transactions",
                    sunToNative(feeLimitSun), "TRX", "SUN");
        }
        ChainAddressRecord deployer = plan.deployer();
        String deployData = plan.deployData();
        TemplateType type = plan.template().type();
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
                return evmFeeQuote(gasPriceWei, gasLimit, false, null);
            });
        } catch (RuntimeException e) {
            log.warn("EVM contract gas estimate fallback: chain={} error={}", profile.getChain(), e.getMessage());
            try {
                BigInteger gasPriceWei = withWeb3(profile, web3j -> web3j.ethGasPrice().send().getGasPrice());
                return evmFeeQuote(gasPriceWei, fallback, true, e.getMessage());
            } catch (RuntimeException nested) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "unable to estimate deployment gas: " + nested.getMessage());
            }
        }
    }

    private FeeQuote nearFeeQuote(DeploymentPlan plan) {
        try {
            BigInteger gasPriceYocto = nearRpcClient.gasPriceYocto();
            if (gasPriceYocto.signum() <= 0) {
                gasPriceYocto = BigInteger.valueOf(100_000_000L);
            }
            BigInteger gasCost = gasPriceYocto.multiply(BigInteger.valueOf(NEAR_CONTRACT_DEPLOY_GAS));
            long storageBytes = plan.template().binary().length + NEAR_EXTRA_STORAGE_BYTES;
            BigInteger storageReserve = NEAR_STORAGE_BYTE_COST_YOCTO.multiply(BigInteger.valueOf(storageBytes));
            BigInteger total = gasCost.add(storageReserve);
            return new FeeQuote(gasPriceYocto, BigInteger.valueOf(NEAR_CONTRACT_DEPLOY_GAS), true,
                    "NEAR deployment reserves estimated gas plus contract-code storage staking; unused gas is not separated from storage reserve in wallet ledger",
                    yoctoToNear(total), "NEAR", "yocto/gas");
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate NEAR deployment gas: " + e.getMessage());
        }
    }

    private FeeQuote suiFeeQuote() {
        try {
            long gasPriceMist = Math.max(1L, suiRpcClient.referenceGasPrice());
            return new FeeQuote(BigInteger.valueOf(gasPriceMist),
                    BigInteger.valueOf(SUI_CONTRACT_DEPLOY_GAS_BUDGET_MIST),
                    true,
                    "Sui package publish uses a fixed gas budget because final storage cost is known only during execution",
                    mistToSui(BigInteger.valueOf(SUI_CONTRACT_DEPLOY_GAS_BUDGET_MIST)),
                    "SUI",
                    "MIST/gas");
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Sui deployment gas: " + e.getMessage());
        }
    }

    private FeeQuote solanaFeeQuote(DeploymentPlan plan) {
        try {
            long mintRent = solanaRpcClient.minimumBalanceForRentExemption(SOLANA_SPL_MINT_ACCOUNT_LENGTH);
            long tokenAccountRent = solanaRpcClient.minimumBalanceForRentExemption(SOLANA_SPL_TOKEN_ACCOUNT_LENGTH);
            long signatureFee = Math.multiplyExact(
                    Math.max(1L, plan.profile().getDefaultFee()),
                    SOLANA_DEPLOY_FEE_SIGNATURES);
            BigInteger totalLamports = BigInteger.valueOf(mintRent)
                    .add(BigInteger.valueOf(tokenAccountRent))
                    .add(BigInteger.valueOf(signatureFee));
            return new FeeQuote(
                    BigInteger.valueOf(Math.max(1L, plan.profile().getDefaultFee())),
                    totalLamports,
                    true,
                    "Solana SPL mint deployment reserves mint-account rent, owner associated-token-account rent, and estimated signature fees",
                    lamportsToSol(totalLamports),
                    "SOL",
                    "lamports/signature");
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Solana deployment fee: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Solana deployment fee: " + e.getMessage());
        }
    }

    private FeeQuote aptosFeeQuote(DeploymentPlan plan) {
        try {
            long gasUnitPrice = Math.max(1L, aptosRpcClient.estimateGasPrice());
            long configuredReserve = Math.max(1L, plan.profile().getDefaultFee());
            long estimatedOctas = Math.multiplyExact(APTOS_DEPLOY_MIN_GAS_AMOUNT, gasUnitPrice);
            long feeLimitOctas = Math.max(configuredReserve, estimatedOctas);
            long gasAmount = Math.max(APTOS_DEPLOY_MIN_GAS_AMOUNT,
                    (feeLimitOctas + gasUnitPrice - 1L) / gasUnitPrice);
            return new FeeQuote(
                    BigInteger.valueOf(gasUnitPrice),
                    BigInteger.valueOf(gasAmount),
                    true,
                    "Aptos package publish reserves a fixed max gas amount because final VM gas is known only after execution",
                    octasToApt(BigInteger.valueOf(Math.multiplyExact(gasAmount, gasUnitPrice))),
                    "APT",
                    "octas/gas");
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Aptos deployment fee: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Aptos deployment fee: " + e.getMessage());
        }
    }

    private FeeQuote polkadotFeeQuote(DeploymentPlan plan) {
        try {
            String assetId = String.valueOf(plan.parameters().parameterView().get("assetId"));
            PolkadotRuntimeClient.AssetInfo existing = polkadotRuntimeClient.assetInfo(assetId);
            if (existing.exists()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Polkadot Asset Hub asset id already exists: " + assetId);
            }
            BigInteger reservePlanck = polkadotDeploymentReservePlanck(plan.profile());
            return new FeeQuote(
                    BigInteger.ONE,
                    reservePlanck,
                    true,
                    "Polkadot Asset Hub asset creation reserves the configured default fee because runtime deposit and dispatch fee are finalized during execution",
                    planckToNative(plan.profile(), reservePlanck),
                    plan.profile().getNativeSymbol(),
                    "planck/reserve");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate Polkadot Asset Hub deployment fee: " + e.getMessage());
        }
    }

    private FeeQuote tonFeeQuote(DeploymentPlan plan) {
        try {
            String contractAddress = String.valueOf(plan.parameters().parameterView().get("contractAddress"));
            String state = tonCenterClient.addressInformation(contractAddress).path("state").asText("");
            if ("active".equalsIgnoreCase(state)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "TON contract address is already active: " + contractAddress);
            }
            BigInteger reserveNano = tonDeploymentReserveNano(plan.profile());
            return new FeeQuote(
                    BigInteger.ONE,
                    reserveNano,
                    true,
                    "TON deployment sends a fixed native reserve with StateInit because final storage and execution fee are known after execution",
                    nanoToTon(reserveNano),
                    "TON",
                    "nano/reserve");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to estimate TON deployment fee: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastDeployment(DeploymentPlan plan, FeeQuote fee) {
        AccountChainProfile profile = plan.profile();
        if (contractFamily(profile) == ContractFamily.SUI) {
            return broadcastSuiDeployment(plan, fee);
        }
        if (contractFamily(profile) == ContractFamily.SOLANA) {
            return broadcastSolanaDeployment(plan);
        }
        if (contractFamily(profile) == ContractFamily.APTOS) {
            return broadcastAptosDeployment(plan, fee);
        }
        if (contractFamily(profile) == ContractFamily.POLKADOT) {
            return broadcastPolkadotDeployment(plan);
        }
        if (contractFamily(profile) == ContractFamily.TON) {
            return broadcastTonDeployment(plan, fee);
        }
        if (contractFamily(profile) == ContractFamily.NEAR) {
            return broadcastNearDeployment(plan, fee);
        }
        if (contractFamily(profile) == ContractFamily.TRON) {
            return broadcastTronDeployment(plan, fee);
        }
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
            return new BroadcastResult(sent.getTransactionHash(), nonce.longValueExact(), null);
        });
    }

    private BroadcastResult broadcastSolanaDeployment(DeploymentPlan plan) {
        try {
            Map<String, Object> params = plan.parameters().parameterView();
            int decimals;
            long initialAtomicAmount;
            boolean retainMintAuthority;
            if (plan.template().type() == TemplateType.ERC20) {
                decimals = Integer.parseInt(String.valueOf(params.get("decimals")));
                initialAtomicAmount = requireSolanaLong(
                        new BigInteger(String.valueOf(params.get("initialSupplyAtomic"))),
                        "initial supply");
                retainMintAuthority = Boolean.TRUE.equals(params.get("mintable"));
            } else {
                decimals = 0;
                initialAtomicAmount = 1L;
                retainMintAuthority = false;
            }
            String memo = "TokDou deploy " + plan.template().type().name() + " "
                    + plan.parameters().symbol() + " " + plan.parameters().name();
            SolanaTransactionService.DeploySplMintResult result = solanaTransactionService.deploySplMint(
                    plan.deployer(),
                    plan.parameters().ownerAddress(),
                    decimals,
                    initialAtomicAmount,
                    retainMintAuthority,
                    memo);
            return new BroadcastResult(result.signature(), null, result.mintAddress());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast Solana contract deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastAptosDeployment(DeploymentPlan plan, FeeQuote fee) {
        try {
            CompiledAptosPackage compiled = plan.aptosCompiledPackage();
            if (compiled == null) {
                throw new IllegalStateException("Aptos package was not compiled");
            }
            AptosTransactionService.DeployPackageResult result = aptosTransactionService.publishPackage(
                    plan.deployer(),
                    compiled.metadata(),
                    compiled.modules(),
                    fee.gasLimit().longValueExact(),
                    fee.gasPriceWei().longValueExact());
            return new BroadcastResult(result.txHash(), result.sequenceNumber(),
                    aptosModuleId(plan.deployer(), plan.aptosMovePackage()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast Aptos contract deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastSuiDeployment(DeploymentPlan plan, FeeQuote fee) {
        try {
            CompiledMovePackage compiled = plan.suiCompiledPackage();
            if (compiled == null) {
                throw new IllegalStateException("Sui package was not compiled");
            }
            SuiTransactionService.PublishResult result = suiTransactionService.publishPackage(
                    plan.deployer(),
                    compiled.modules(),
                    compiled.dependencies(),
                    fee.gasLimit().longValueExact());
            if (!suiTxSucceeded(result.result())) {
                throw new IllegalStateException(suiTxError(result.result()));
            }
            return new BroadcastResult(result.digest(), null, null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast Sui contract deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastPolkadotDeployment(DeploymentPlan plan) {
        try {
            Map<String, Object> params = plan.parameters().parameterView();
            int decimals = Integer.parseInt(String.valueOf(params.getOrDefault("decimals", 0)));
            BigInteger initialSupply = new BigInteger(String.valueOf(
                    params.getOrDefault("initialSupplyAtomic", "0")));
            boolean mintable = plan.template().type() == TemplateType.ERC20
                    && Boolean.TRUE.equals(params.get("mintable"));
            PolkadotTransactionService.DeployAssetResult result = polkadotTransactionService.deployAsset(
                    plan.deployer(),
                    String.valueOf(params.get("assetId")),
                    plan.parameters().name(),
                    plan.parameters().symbol(),
                    decimals,
                    new BigInteger(String.valueOf(params.getOrDefault("minBalance",
                            POLKADOT_ASSET_HUB_MIN_BALANCE.toString()))),
                    initialSupply,
                    mintable);
            return new BroadcastResult(result.txHash(), null, result.assetId());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast Polkadot Asset Hub deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastTonDeployment(DeploymentPlan plan, FeeQuote fee) {
        try {
            Map<String, Object> params = plan.parameters().parameterView();
            String contractAddress = String.valueOf(params.get("contractAddress"));
            Cell body = null;
            org.ton.ton4j.tlb.StateInit stateInit;
            if (plan.template().type() == TemplateType.ERC20) {
                JettonMinter minter = tonJettonMinter(plan.parameters().ownerAddress(),
                        String.valueOf(params.get("metadataUri")));
                stateInit = minter.getStateInit();
                BigInteger initialSupply = new BigInteger(String.valueOf(
                        params.getOrDefault("initialSupplyAtomic", "0")));
                if (initialSupply.signum() > 0) {
                    org.ton.ton4j.address.Address owner =
                            org.ton.ton4j.address.Address.of(plan.parameters().ownerAddress());
                    body = JettonMinter.createMintBody(
                            System.currentTimeMillis(),
                            owner,
                            TON_JETTON_WALLET_DEPLOY_NANO,
                            initialSupply,
                            owner,
                            owner,
                            TON_JETTON_FORWARD_NANO,
                            null);
                }
            } else {
                NftCollection collection = tonNftCollection(
                        plan.parameters().ownerAddress(),
                        String.valueOf(params.get("collectionUri")),
                        String.valueOf(params.get("itemBaseUri")));
                stateInit = collection.getStateInit();
            }
            TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareContractCall(
                    plan.deployer(),
                    contractAddress,
                    fee.gasLimit(),
                    stateInit,
                    body,
                    false);
            String txHash = tonTransactionService.broadcast(prepared);
            return new BroadcastResult(txHash, prepared.seqno(), contractAddress);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast TON deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastTronDeployment(DeploymentPlan plan, FeeQuote fee) {
        try (TronTridentClient client = tronClientFactory.create()) {
            KeyPair keyPair = TronTridentKeyFactory.fromBitcoinEcKey(keyService.key(plan.profile(), plan.deployer()));
            Response.TransactionExtention tx = client.deployContract(
                    keyPair,
                    plan.template().contractName(),
                    plan.template().abiJson(),
                    strip0x(plan.template().bytecode()),
                    plan.parameters().tronArguments(),
                    fee.gasLimit().longValueExact(),
                    TRON_CONSUME_USER_RESOURCE_PERCENT,
                    TRON_ORIGIN_ENERGY_LIMIT,
                    0L);
            if (tx.hasResult() && !tx.getResult().getResult()) {
                throw new IllegalStateException(tx.getResult().getMessage().toStringUtf8());
            }
            org.tron.trident.proto.Chain.Transaction signed = client.api().signTransaction(tx, keyPair);
            String txHash = TronTransactionService.txId(signed);
            client.broadcast(signed);
            return new BroadcastResult(txHash, null, null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast TRON contract deployment: " + e.getMessage());
        }
    }

    private BroadcastResult broadcastNearDeployment(DeploymentPlan plan, FeeQuote fee) {
        try {
            NearTransactionService.DeployResult result = nearTransactionService.deployContractAndInit(
                    plan.deployer(),
                    plan.template().binary(),
                    "init",
                    plan.constructorData().getBytes(StandardCharsets.UTF_8),
                    fee.gasLimit().longValueExact(),
                    BigInteger.ZERO);
            if (!nearTxSucceeded(result.result())) {
                throw new IllegalStateException(nearTxError(result.result()));
            }
            return new BroadcastResult(result.txHash(), result.nonce(), null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "unable to broadcast NEAR contract deployment: " + e.getMessage());
        }
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
                    plan.sourceCode(),
                    plan.template().abiJson(),
                    plan.bytecodeHash(),
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

    private void updateOrderSent(String chain, String orderNo, String txHash, Long nonce, String contractAddress) {
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = 'SENT',
                       tx_hash = ?,
                       nonce = ?,
                       contract_address = coalesce(?, contract_address),
                       updated_at = now()
                 where chain = ? and order_no = ?
                """, txHash, nonce, contractAddress, chain, orderNo);
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
                select id, order_no, chain, tx_hash, contract_address, template_type,
                       deployer_address, account_id, native_symbol, fee_limit, gas_price_wei
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
        AccountChainProfile profile = requireContractProfile(chain);
        if (contractFamily(profile) == ContractFamily.SUI) {
            refreshPendingSuiOrder(profile, row, txHash);
            return;
        }
        if (contractFamily(profile) == ContractFamily.SOLANA) {
            refreshPendingSolanaOrder(profile, row, txHash);
            return;
        }
        if (contractFamily(profile) == ContractFamily.APTOS) {
            refreshPendingAptosOrder(profile, row, txHash);
            return;
        }
        if (contractFamily(profile) == ContractFamily.POLKADOT) {
            refreshPendingPolkadotOrder(profile, row, txHash);
            return;
        }
        if (contractFamily(profile) == ContractFamily.TON) {
            refreshPendingTonOrder(profile, row);
            return;
        }
        if (contractFamily(profile) == ContractFamily.NEAR) {
            refreshPendingNearOrder(profile, row, txHash);
            return;
        }
        if (contractFamily(profile) == ContractFamily.TRON) {
            refreshPendingTronOrder(profile, row, txHash);
            return;
        }
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

    private void refreshPendingSuiOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        JsonNode transaction = suiRpcClient.transactionBlock(txHash);
        String status = transaction.path("effects").path("status").path("status").asText("");
        if ("success".equals(status)) {
            BigDecimal actualFee = mistToSui(BigInteger.valueOf(suiTotalGas(transaction.path("effects").path("gasUsed"))));
            if (actualFee.signum() > 0) {
                repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, actualFee);
            }
            BigDecimal release = feeLimit.subtract(actualFee);
            if (release.signum() > 0) {
                repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, release);
            }
            jdbcTemplate.update("""
                    update contract_deployment_order
                       set status = 'CONFIRMED',
                           contract_address = ?,
                           fee_actual = ?,
                           block_height = ?,
                           confirmations = 1,
                           error_message = null,
                           updated_at = now()
                     where chain = ? and order_no = ?
                    """,
                    suiPublishedPackageId(transaction),
                    actualFee,
                    transaction.path("checkpoint").asLong(0L),
                    profile.getChain(),
                    orderNo);
            return;
        }
        if ("failure".equals(status)) {
            BigDecimal actualFee = mistToSui(BigInteger.valueOf(suiTotalGas(transaction.path("effects").path("gasUsed"))));
            if (actualFee.signum() > 0) {
                repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, actualFee);
            }
            BigDecimal release = feeLimit.subtract(actualFee);
            if (release.signum() > 0) {
                repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, release);
            }
            jdbcTemplate.update("""
                    update contract_deployment_order
                       set status = 'FAILED',
                           fee_actual = ?,
                           block_height = ?,
                           confirmations = 1,
                           error_message = ?,
                           updated_at = now()
                     where chain = ? and order_no = ?
                    """,
                    actualFee,
                    transaction.path("checkpoint").asLong(0L),
                    suiTxError(transaction),
                    profile.getChain(),
                    orderNo);
        }
    }

    private void refreshPendingSolanaOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        JsonNode status = solanaRpcClient.getSignatureStatus(txHash);
        if (status == null || status.isNull()) {
            return;
        }
        boolean failed = status.has("err") && !status.path("err").isNull() && !status.path("err").isMissingNode();
        String confirmationStatus = status.path("confirmationStatus").asText("");
        int confirmations = solanaConfirmations(status);
        boolean finalized = "finalized".equals(confirmationStatus);
        if (!failed && !"confirmed".equals(confirmationStatus) && !finalized) {
            return;
        }
        int required = Math.max(1, profile.getWithdrawConfirmations());
        if (!failed && !finalized && confirmations < required) {
            return;
        }
        JsonNode transaction = solanaRpcClient.getTransaction(txHash);
        if (transaction == null || transaction.isNull()) {
            return;
        }
        JsonNode meta = transaction.path("meta");
        if (meta.has("err") && !meta.path("err").isNull() && !meta.path("err").isMissingNode()) {
            failed = true;
        }
        long feeLamports = Math.max(0L, meta.path("fee").asLong(0L));
        long actualLamports = failed ? feeLamports : Math.addExact(feeLamports, solanaDeploymentRentLamports());
        BigDecimal actualFee = lamportsToSol(actualLamports);
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        if (actualFee.signum() > 0) {
            repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, actualFee);
        }
        BigDecimal release = feeLimit.subtract(actualFee);
        if (release.signum() > 0) {
            repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, release);
        }
        Object contractAddress = row.get("contract_address");
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
                failed ? "FAILED" : "CONFIRMED",
                failed ? null : contractAddress,
                actualFee,
                transaction.path("slot").asLong(0L),
                confirmations,
                failed ? solanaTxError(status, meta) : null,
                profile.getChain(),
                orderNo);
    }

    private long solanaDeploymentRentLamports() {
        return Math.addExact(
                solanaRpcClient.minimumBalanceForRentExemption(SOLANA_SPL_MINT_ACCOUNT_LENGTH),
                solanaRpcClient.minimumBalanceForRentExemption(SOLANA_SPL_TOKEN_ACCOUNT_LENGTH));
    }

    private static int solanaConfirmations(JsonNode status) {
        JsonNode confirmations = status.path("confirmations");
        if (confirmations.isNull() || confirmations.isMissingNode()) {
            return 32;
        }
        return Math.max(1, confirmations.asInt(1));
    }

    private static String solanaTxError(JsonNode status, JsonNode meta) {
        JsonNode metaErr = meta == null ? null : meta.path("err");
        if (metaErr != null && !metaErr.isNull() && !metaErr.isMissingNode()) {
            return "Solana contract deployment transaction failed: " + metaErr;
        }
        JsonNode statusErr = status == null ? null : status.path("err");
        if (statusErr != null && !statusErr.isNull() && !statusErr.isMissingNode()) {
            return "Solana contract deployment transaction failed: " + statusErr;
        }
        return "Solana contract deployment transaction failed";
    }

    private void refreshPendingAptosOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        JsonNode transaction = aptosRpcClient.transactionByHash(txHash);
        if (transaction == null || transaction.isNull()
                || !"user_transaction".equals(transaction.path("type").asText(""))) {
            return;
        }
        long gasUsed = transaction.path("gas_used").asLong(0L);
        long gasUnitPrice = transaction.path("gas_unit_price").asLong(0L);
        BigDecimal actualFee = octasToApt(BigInteger.valueOf(Math.max(0L, gasUsed))
                .multiply(BigInteger.valueOf(Math.max(0L, gasUnitPrice))));
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        if (actualFee.signum() > 0) {
            repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, actualFee);
        }
        BigDecimal release = feeLimit.subtract(actualFee);
        if (release.signum() > 0) {
            repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, release);
        }
        boolean success = transaction.path("success").asBoolean(false);
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = ?,
                       contract_address = ?,
                       fee_actual = ?,
                       block_height = ?,
                       confirmations = 1,
                       error_message = ?,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """,
                success ? "CONFIRMED" : "FAILED",
                success ? row.get("contract_address") : null,
                actualFee,
                transaction.path("version").asLong(0L),
                success ? null : aptosTxError(transaction),
                profile.getChain(),
                orderNo);
    }

    private static String aptosTxError(JsonNode transaction) {
        String status = transaction == null ? "" : transaction.path("vm_status").asText("");
        return status.isBlank() ? "Aptos contract deployment transaction failed" : status;
    }

    private void refreshPendingPolkadotOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        int lookback = Math.max(512, Math.max(1, profile.getWithdrawConfirmations()) * 20);
        if (!polkadotRuntimeClient.assetTransactionFinalized(txHash, lookback)) {
            return;
        }
        Object assetValue = row.get("contract_address");
        String assetId = assetValue == null ? "" : String.valueOf(assetValue);
        if (assetId.isBlank() || !polkadotRuntimeClient.assetInfo(assetId).exists()) {
            return;
        }
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        if (feeLimit.signum() > 0) {
            repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, feeLimit);
        }
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = 'CONFIRMED',
                       contract_address = ?,
                       fee_actual = ?,
                       confirmations = 1,
                       error_message = null,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """,
                assetId,
                feeLimit,
                profile.getChain(),
                orderNo);
    }

    private void refreshPendingTonOrder(AccountChainProfile profile, Map<String, Object> row) {
        Object contractValue = row.get("contract_address");
        String contractAddress = contractValue == null ? "" : String.valueOf(contractValue);
        if (contractAddress.isBlank()) {
            return;
        }
        String state = tonCenterClient.addressInformation(contractAddress).path("state").asText("");
        if (!"active".equalsIgnoreCase(state)) {
            return;
        }
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        if (feeLimit.signum() > 0) {
            repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, feeLimit);
        }
        jdbcTemplate.update("""
                update contract_deployment_order
                   set status = 'CONFIRMED',
                       contract_address = ?,
                       fee_actual = ?,
                       confirmations = 1,
                       error_message = null,
                       updated_at = now()
                 where chain = ? and order_no = ?
                """,
                contractAddress,
                feeLimit,
                profile.getChain(),
                orderNo);
    }

    private static boolean suiTxSucceeded(JsonNode result) {
        return "success".equals(result.path("effects").path("status").path("status").asText(""));
    }

    private static String suiTxError(JsonNode result) {
        String error = result.path("effects").path("status").path("error").asText("");
        return error.isBlank() ? "Sui contract deployment transaction failed" : error;
    }

    private static long suiTotalGas(JsonNode gas) {
        long total = gas.path("computationCost").asLong(0)
                + gas.path("storageCost").asLong(0)
                - gas.path("storageRebate").asLong(0);
        return Math.max(0L, total);
    }

    private static String suiPublishedPackageId(JsonNode transaction) {
        for (JsonNode change : transaction.path("objectChanges")) {
            if ("published".equals(change.path("type").asText(""))) {
                String packageId = change.path("packageId").asText("");
                if (!packageId.isBlank()) {
                    return packageId;
                }
            }
        }
        return null;
    }

    private void refreshPendingNearOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        String sender = String.valueOf(row.get("deployer_address"));
        String orderNo = String.valueOf(row.get("order_no"));
        String accountId = String.valueOf(row.get("account_id"));
        String nativeSymbol = String.valueOf(row.get("native_symbol"));
        BigDecimal feeLimit = decimal(row.get("fee_limit"));
        JsonNode status = nearRpcClient.transactionStatus(txHash, sender);
        if (nearTxSucceeded(status)) {
            if (feeLimit.signum() > 0) {
                repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, feeLimit);
            }
            jdbcTemplate.update("""
                    update contract_deployment_order
                       set status = 'CONFIRMED',
                           contract_address = ?,
                           fee_actual = ?,
                           block_height = ?,
                           confirmations = 1,
                           error_message = null,
                           updated_at = now()
                     where chain = ? and order_no = ?
                    """,
                    sender,
                    feeLimit,
                    nearBlockHeight(status),
                    profile.getChain(),
                    orderNo);
            return;
        }
        JsonNode failure = status.path("status").path("Failure");
        if (!failure.isMissingNode()) {
            if (feeLimit.signum() > 0) {
                repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, feeLimit);
            }
            jdbcTemplate.update("""
                    update contract_deployment_order
                       set status = 'FAILED',
                           fee_actual = 0,
                           block_height = ?,
                           confirmations = 1,
                           error_message = ?,
                           updated_at = now()
                     where chain = ? and order_no = ?
                    """,
                    nearBlockHeight(status),
                    failure.toString(),
                    profile.getChain(),
                    orderNo);
        }
    }

    private static boolean nearTxSucceeded(JsonNode result) {
        JsonNode status = result == null ? null : result.path("status");
        return status != null && (status.has("SuccessValue") || status.has("SuccessReceiptId"));
    }

    private static String nearTxError(JsonNode result) {
        JsonNode failure = result == null ? null : result.path("status").path("Failure");
        return failure == null || failure.isMissingNode()
                ? "NEAR contract deployment transaction failed"
                : failure.toString();
    }

    private static long nearBlockHeight(JsonNode result) {
        long height = result.path("transaction_outcome").path("block_height").asLong(0L);
        for (JsonNode receipt : result.path("receipts_outcome")) {
            height = Math.max(height, receipt.path("block_height").asLong(0L));
        }
        return height;
    }

    private void refreshPendingTronOrder(AccountChainProfile profile, Map<String, Object> row, String txHash) {
        Response.TransactionInfo info;
        try (TronTridentClient client = tronClientFactory.create()) {
            info = client.getTransactionInfo(txHash, NodeType.SOLIDITY_NODE);
            if (info == null || info.getId().isEmpty() || info.getBlockNumber() <= 0) {
                return;
            }
            long latest = client.getNowBlock().getBlockHeader().getRawData().getNumber();
            long confirmations = Math.max(0L, latest - info.getBlockNumber() + 1L);
            int required = Math.max(1, profile.getWithdrawConfirmations());
            if (confirmations < required) {
                return;
            }
            BigDecimal actualFee = sunToNative(info.getFee());
            BigDecimal feeLimit = decimal(row.get("fee_limit"));
            String orderNo = String.valueOf(row.get("order_no"));
            String accountId = String.valueOf(row.get("account_id"));
            String nativeSymbol = String.valueOf(row.get("native_symbol"));
            if (actualFee.signum() > 0) {
                repository.settleLockedDebit(profile.getChain(), nativeSymbol, accountId, actualFee);
            }
            BigDecimal release = feeLimit.subtract(actualFee);
            if (release.signum() > 0) {
                repository.releaseLockedBalance(profile.getChain(), nativeSymbol, accountId, release);
            }
            boolean success = info.getResult() == Response.TransactionInfo.code.SUCESS;
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
                    success ? "CONFIRMED" : "FAILED",
                    tronContractAddress(info),
                    actualFee,
                    info.getBlockNumber(),
                    (int) Math.min(confirmations, Integer.MAX_VALUE),
                    success ? null : tronReceiptError(info),
                    profile.getChain(),
                    orderNo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("unable to refresh TRON deployment receipt", e);
        }
    }

    private static String tronContractAddress(Response.TransactionInfo info) {
        if (info == null || info.getContractAddress().isEmpty()) {
            return null;
        }
        return TronAddressCodec.hexToBase58(ApiWrapper.toHex(info.getContractAddress()));
    }

    private static String tronReceiptError(Response.TransactionInfo info) {
        if (info == null) {
            return "TRON contract deployment transaction failed";
        }
        String message = info.getResMessage().isEmpty() ? "" : info.getResMessage().toStringUtf8();
        return message.isBlank()
                ? "TRON contract deployment transaction failed: " + info.getResult().name()
                : message;
    }

    private ContractParameters erc20Parameters(ContractFamily family, AccountChainProfile profile,
                                               ChainAddressRecord deployer,
                                               ContractDeployRequest request, String ownerAddress) {
        int decimals = request.decimals() == null ? 18 : request.decimals();
        int maxDecimals = family == ContractFamily.SOLANA ? 9 : family == ContractFamily.APTOS ? 8 : 18;
        if (decimals < 0 || decimals > maxDecimals) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    family == ContractFamily.SOLANA
                            ? "SPL token decimals must be between 0 and 9"
                            : family == ContractFamily.APTOS
                            ? "Aptos Coin decimals must be between 0 and 8"
                            : "ERC20 decimals must be between 0 and 18");
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
        params.put("initialSupplyAtomic", initialSupply.toString());
        params.put("maxSupplyAtomic", maxSupply.toString());
        params.put("mintable", mintable);
        params.put("ownerAddress", ownerAddress);
        if (family == ContractFamily.TON) {
            String metadataUri = tonMetadataUri("jetton", symbol, null);
            JettonMinter minter = tonJettonMinter(ownerAddress, metadataUri);
            params.put("metadataUri", metadataUri);
            params.put("contractAddress", tonFriendly(minter.getAddress(), profile));
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("ton.jetton.deploy", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.POLKADOT) {
            String assetId = polkadotAssetId(profile, deployer, TemplateType.ERC20, params);
            params.put("assetId", assetId);
            params.put("minBalance", POLKADOT_ASSET_HUB_MIN_BALANCE.toString());
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("polkadot.asset-hub.create", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.SOLANA) {
            requireSolanaLong(initialSupply, "initial supply");
            requireSolanaLong(maxSupply, "max supply");
            return new ContractParameters(name, symbol, ownerAddress, params,
                    solanaConstructorView("spl.mint", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.APTOS) {
            requireAptosU64(initialSupply, "initial supply");
            requireAptosU64(maxSupply, "max supply");
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("aptos.coin.init", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.SUI) {
            requireSuiU64(initialSupply, "initial supply");
            requireSuiU64(maxSupply, "max supply");
            return new ContractParameters(name, symbol, ownerAddress, params,
                    suiConstructorView(params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.NEAR) {
            String initJson = nearFtInitJson(ownerAddress, name, symbol, decimals, initialSupply);
            return new ContractParameters(name, symbol, ownerAddress, params,
                    nearConstructorView(initJson),
                    List.of(), List.of(), initJson);
        }
        List<Type> args = List.of(
                new Utf8String(name),
                new Utf8String(symbol),
                new Uint8(BigInteger.valueOf(decimals)),
                new Uint256(initialSupply),
                new Uint256(maxSupply),
                new Address(family == ContractFamily.TRON ? "0x" + TronAddressCodec.base58ToHex(ownerAddress).substring(2) : ownerAddress),
                new Bool(mintable));
        List<org.tron.trident.abi.datatypes.Type<?>> tronArgs = family == ContractFamily.TRON
                ? List.of(
                        new org.tron.trident.abi.datatypes.Utf8String(name),
                        new org.tron.trident.abi.datatypes.Utf8String(symbol),
                        new org.tron.trident.abi.datatypes.generated.Uint8(BigInteger.valueOf(decimals)),
                        new org.tron.trident.abi.datatypes.generated.Uint256(initialSupply),
                        new org.tron.trident.abi.datatypes.generated.Uint256(maxSupply),
                        new org.tron.trident.abi.datatypes.Address(TronAddressCodec.toAbiAddress(ownerAddress)),
                        new org.tron.trident.abi.datatypes.Bool(mintable))
                : List.of();
        return new ContractParameters(name, symbol, ownerAddress, params,
                family == ContractFamily.TRON ? tronConstructorView(tronArgs) : constructorView(args),
                args, tronArgs, "");
    }

    private ContractParameters erc721Parameters(ContractFamily family, AccountChainProfile profile,
                                                ChainAddressRecord deployer,
                                                ContractDeployRequest request, String ownerAddress) {
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
        if (family == ContractFamily.TON) {
            String collectionUri = tonMetadataUri("nft-collection", symbol, baseUri);
            String itemBaseUri = tonItemBaseUri(symbol, baseUri);
            NftCollection collection = tonNftCollection(ownerAddress, collectionUri, itemBaseUri);
            params.put("collectionUri", collectionUri);
            params.put("itemBaseUri", itemBaseUri);
            params.put("contractAddress", tonFriendly(collection.getAddress(), profile));
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("ton.nft-collection.deploy", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.POLKADOT) {
            if (!BigInteger.ONE.equals(maxSupply)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Polkadot Asset Hub single asset currently requires max supply 1");
            }
            String assetId = polkadotAssetId(profile, deployer, TemplateType.ERC721, params);
            params.put("assetId", assetId);
            params.put("decimals", 0);
            params.put("initialSupplyAtomic", "1");
            params.put("minBalance", POLKADOT_ASSET_HUB_MIN_BALANCE.toString());
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("polkadot.asset-hub.single-asset.create", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.SOLANA) {
            if (!BigInteger.ONE.equals(maxSupply)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Solana SPL NFT mint currently requires max supply 1");
            }
            return new ContractParameters(name, symbol, ownerAddress, params,
                    solanaConstructorView("spl.nft-mint", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.APTOS) {
            if (!BigInteger.ONE.equals(maxSupply)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Aptos single-asset mint currently requires max supply 1");
            }
            return new ContractParameters(name, symbol, ownerAddress, params,
                    jsonConstructorView("aptos.single-asset.init", params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.SUI) {
            requireSuiU64(maxSupply, "collection max supply");
            return new ContractParameters(name, symbol, ownerAddress, params,
                    suiConstructorView(params),
                    List.of(), List.of(), "");
        }
        if (family == ContractFamily.NEAR) {
            String initJson = nearNftInitJson(ownerAddress, name, symbol, baseUri);
            return new ContractParameters(name, symbol, ownerAddress, params,
                    nearConstructorView(initJson),
                    List.of(), List.of(), initJson);
        }
        List<Type> args = List.of(
                new Utf8String(name),
                new Utf8String(symbol),
                new Utf8String(baseUri),
                new Uint256(maxSupply),
                new Address(family == ContractFamily.TRON ? "0x" + TronAddressCodec.base58ToHex(ownerAddress).substring(2) : ownerAddress));
        List<org.tron.trident.abi.datatypes.Type<?>> tronArgs = family == ContractFamily.TRON
                ? List.of(
                        new org.tron.trident.abi.datatypes.Utf8String(name),
                        new org.tron.trident.abi.datatypes.Utf8String(symbol),
                        new org.tron.trident.abi.datatypes.Utf8String(baseUri),
                        new org.tron.trident.abi.datatypes.generated.Uint256(maxSupply),
                        new org.tron.trident.abi.datatypes.Address(TronAddressCodec.toAbiAddress(ownerAddress)))
                : List.of();
        return new ContractParameters(name, symbol, ownerAddress, params,
                family == ContractFamily.TRON ? tronConstructorView(tronArgs) : constructorView(args),
                args, tronArgs, "");
    }

    private String constructorData(ContractFamily family, TemplateType type, ContractParameters parameters) {
        try {
            if (family == ContractFamily.NEAR) {
                return parameters.nearArgumentsJson();
            }
            if (family == ContractFamily.SOLANA) {
                return json(parameters.parameterView());
            }
            if (family == ContractFamily.APTOS) {
                return json(parameters.parameterView());
            }
            if (family == ContractFamily.SUI) {
                return json(parameters.parameterView());
            }
            if (family == ContractFamily.POLKADOT) {
                return json(parameters.parameterView());
            }
            if (family == ContractFamily.TON) {
                return json(parameters.parameterView());
            }
            if (family == ContractFamily.TRON) {
                return ApiWrapper.toHex(Utils.encodeParameter(parameters.tronArguments()));
            }
            return FunctionEncoder.encodeConstructor(parameters.evmArguments());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid " + type.name() + " constructor arguments");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid " + type.name() + " constructor arguments");
        }
    }

    private String nearFtInitJson(String ownerAddress, String name, String symbol,
                                  int decimals, BigInteger initialSupply) {
        Map<String, Object> metadata = orderedMap();
        metadata.put("spec", "ft-1.0.0");
        metadata.put("name", name);
        metadata.put("symbol", symbol);
        metadata.put("icon", null);
        metadata.put("reference", null);
        metadata.put("reference_hash", null);
        metadata.put("decimals", decimals);
        Map<String, Object> args = orderedMap();
        args.put("owner_id", ownerAddress);
        args.put("total_supply", initialSupply.toString());
        args.put("metadata", metadata);
        return json(args);
    }

    private String nearNftInitJson(String ownerAddress, String name, String symbol, String baseUri) {
        Map<String, Object> metadata = orderedMap();
        metadata.put("spec", "nft-1.0.0");
        metadata.put("name", name);
        metadata.put("symbol", symbol);
        metadata.put("icon", null);
        metadata.put("base_uri", baseUri == null || baseUri.isBlank() ? null : baseUri);
        metadata.put("reference", null);
        metadata.put("reference_hash", null);
        Map<String, Object> args = orderedMap();
        args.put("owner_id", ownerAddress);
        args.put("metadata", metadata);
        return json(args);
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

    private List<Map<String, Object>> tronConstructorView(List<org.tron.trident.abi.datatypes.Type<?>> args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (org.tron.trident.abi.datatypes.Type<?> arg : args) {
            Map<String, Object> row = orderedMap();
            row.put("type", arg.getTypeAsString());
            row.put("value", String.valueOf(arg.getValue()));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> nearConstructorView(String initJson) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = orderedMap();
        row.put("type", "json");
        row.put("value", initJson);
        rows.add(row);
        return rows;
    }

    private List<Map<String, Object>> suiConstructorView(Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = orderedMap();
        row.put("type", "move.init");
        row.put("value", params);
        rows.add(row);
        return rows;
    }

    private List<Map<String, Object>> solanaConstructorView(String type, Map<String, Object> params) {
        return jsonConstructorView(type, params);
    }

    private List<Map<String, Object>> jsonConstructorView(String type, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = orderedMap();
        row.put("type", type);
        row.put("value", params);
        rows.add(row);
        return rows;
    }

    private String polkadotAssetId(AccountChainProfile profile, ChainAddressRecord deployer,
                                   TemplateType type, Map<String, Object> params) {
        String seed = profile.getChain() + ":" + profile.getNetwork() + ":"
                + deployer.getAddress() + ":" + type.name() + ":" + json(params);
        BigInteger hash = new BigInteger(sha256Hex(seed), 16);
        return hash.mod(POLKADOT_ASSET_ID_MOD).add(POLKADOT_ASSET_ID_OFFSET).toString();
    }

    private JettonMinter tonJettonMinter(String adminAddress, String metadataUri) {
        return JettonMinter.builder()
                .adminAddress(org.ton.ton4j.address.Address.of(adminAddress))
                .content(NftUtils.createOffChainUriCell(metadataUri))
                .wc(0)
                .build();
    }

    private NftCollection tonNftCollection(String adminAddress, String collectionUri, String itemBaseUri) {
        return NftCollection.builder()
                .adminAddress(org.ton.ton4j.address.Address.of(adminAddress))
                .collectionContentUri(collectionUri)
                .collectionContentBaseUri(itemBaseUri)
                .royalty(0.0)
                .royaltyAddress(org.ton.ton4j.address.Address.of(adminAddress))
                .wc(0)
                .build();
    }

    private static String tonMetadataUri(String kind, String symbol, String baseUri) {
        String normalizedSymbol = (symbol == null ? "TOKEN" : symbol).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        String normalizedBase = trim(baseUri, 300);
        if (!normalizedBase.isBlank()) {
            String separator = normalizedBase.endsWith("/") ? "" : "/";
            return normalizedBase + separator + kind + "-" + normalizedSymbol + ".json";
        }
        return "https://tokdou.com/ton/" + kind + "-" + normalizedSymbol + ".json";
    }

    private static String tonItemBaseUri(String symbol, String baseUri) {
        String normalizedSymbol = (symbol == null ? "NFT" : symbol).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        String normalizedBase = trim(baseUri, 300);
        if (!normalizedBase.isBlank()) {
            return normalizedBase.endsWith("/") ? normalizedBase : normalizedBase + "/";
        }
        return "https://tokdou.com/ton/nft-items/" + normalizedSymbol + "/";
    }

    private RenderedAptosPackage renderAptosMovePackage(CompiledTemplate template, TemplateType type,
                                                        ContractParameters parameters,
                                                        ChainAddressRecord deployer) {
        Map<String, Object> params = parameters.parameterView();
        String addressName = "TokDouAptos";
        String moduleName = aptosModuleName(type, parameters);
        String coinTypeName = aptosCoinTypeName(type, parameters);
        String source = template.source()
                .replace("{{ADDRESS_NAME}}", addressName)
                .replace("{{MODULE_NAME}}", moduleName)
                .replace("{{COIN_TYPE}}", coinTypeName)
                .replace("{{NAME}}", parameters.name())
                .replace("{{SYMBOL}}", parameters.symbol());
        if (type == TemplateType.ERC20) {
            source = source
                    .replace("{{DECIMALS}}", String.valueOf(params.get("decimals")))
                    .replace("{{INITIAL_SUPPLY}}", String.valueOf(params.get("initialSupplyAtomic")))
                    .replace("{{MAX_SUPPLY}}", String.valueOf(params.get("maxSupplyAtomic")))
                    .replace("{{MINTABLE}}", Boolean.TRUE.equals(params.get("mintable")) ? "true" : "false")
                    .replace("{{INITIAL_MINT_BLOCK}}", aptosInitialMintBlock(params));
        }
        String packageName = "TokDouAptos" + (type == TemplateType.ERC20 ? "Coin" : "Asset");
        return new RenderedAptosPackage(
                packageName,
                addressName,
                moduleName,
                moduleName + ".move",
                aptosNormalizeAddress(deployer.getAddress()),
                source,
                aptosMoveToml(packageName, addressName));
    }

    private String aptosModuleName(TemplateType type, ContractParameters parameters) {
        String symbolPart = moveIdentifier(parameters.symbol().toLowerCase(Locale.ROOT));
        String seed = type.name() + ":" + json(parameters.parameterView());
        String suffix = sha256Hex(seed).substring(0, 10).toLowerCase(Locale.ROOT);
        return "tokdou_aptos_" + (type == TemplateType.ERC20 ? "coin" : "asset")
                + "_" + symbolPart + "_" + suffix;
    }

    private String aptosCoinTypeName(TemplateType type, ContractParameters parameters) {
        String symbolPart = moveIdentifier(parameters.symbol().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
        String suffix = sha256Hex(type.name() + ":" + parameters.symbol() + ":" + parameters.name())
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
        return (type == TemplateType.ERC20 ? "Coin" : "Asset") + "_" + symbolPart + "_" + suffix;
    }

    private static String aptosModuleId(ChainAddressRecord deployer, RenderedAptosPackage movePackage) {
        if (movePackage == null) {
            return null;
        }
        return aptosNormalizeAddress(deployer.getAddress()) + "::" + movePackage.moduleName();
    }

    private static String moveIdentifier(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "x_" + normalized;
        }
        return normalized;
    }

    private static String aptosMoveToml(String packageName, String addressName) {
        return """
                [package]
                name = "%s"
                version = "1.0.0"

                [addresses]
                %s = "_"

                [dependencies.AptosFramework]
                git = "https://github.com/aptos-labs/aptos-core.git"
                rev = "mainnet"
                subdir = "aptos-move/framework/aptos-framework"
                """.formatted(packageName, addressName);
    }

    private static String aptosInitialMintBlock(Map<String, Object> params) {
        String initialSupply = String.valueOf(params.get("initialSupplyAtomic"));
        return """
                if (%s > 0) {
                    mint(sender, signer::address_of(sender), %s);
                };
                """.formatted(initialSupply, initialSupply);
    }

    private RenderedMovePackage renderSuiMovePackage(CompiledTemplate template, TemplateType type,
                                                     ContractParameters parameters) {
        Map<String, Object> params = parameters.parameterView();
        String moduleName = suiModuleName(type, parameters);
        String source = template.source()
                .replace("{{MODULE_NAME}}", moduleName)
                .replace("{{WITNESS_NAME}}", moduleName.toUpperCase(Locale.ROOT))
                .replace("{{OWNER_ADDRESS}}", suiNormalizeAddress(parameters.ownerAddress()))
                .replace("{{NAME_HEX}}", utf8Hex(parameters.name()))
                .replace("{{SYMBOL_HEX}}", utf8Hex(parameters.symbol()));
        if (type == TemplateType.ERC20) {
            boolean mintable = Boolean.TRUE.equals(params.get("mintable"));
            source = source
                    .replace("{{DECIMALS}}", String.valueOf(params.get("decimals")))
                    .replace("{{DESCRIPTION_HEX}}", utf8Hex("TokDou deployed Sui Coin"))
                    .replace("{{INITIAL_SUPPLY}}", String.valueOf(params.get("initialSupplyAtomic")))
                    .replace("{{MAX_SUPPLY}}", String.valueOf(params.get("maxSupplyAtomic")))
                    .replace("{{INITIAL_MINT_BLOCK}}", suiInitialMintBlock())
                    .replace("{{AUTHORITY_BLOCK}}", suiMintAuthorityBlock(mintable));
        } else {
            source = source
                    .replace("{{BASE_URI_HEX}}", utf8Hex(String.valueOf(params.getOrDefault("baseUri", ""))))
                    .replace("{{MAX_SUPPLY}}", String.valueOf(params.get("maxSupply")));
        }
        return new RenderedMovePackage(moduleName, moduleName, moduleName + ".move", source, suiMoveToml(moduleName));
    }

    private String suiModuleName(TemplateType type, ContractParameters parameters) {
        String symbolPart = parameters.symbol().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        if (symbolPart.isBlank() || !Character.isLetter(symbolPart.charAt(0))) {
            symbolPart = "x_" + symbolPart;
        }
        String seed = type.name() + ":" + json(parameters.parameterView());
        String suffix = sha256Hex(seed).substring(0, 10).toLowerCase(Locale.ROOT);
        return "tokdou_sui_" + (type == TemplateType.ERC20 ? "coin" : "nft")
                + "_" + symbolPart + "_" + suffix;
    }

    private static String suiMoveToml(String moduleName) {
        return """
                [package]
                name = "%s"
                edition = "2024.beta"

                [addresses]
                %s = "0x0"
                """.formatted(moduleName, moduleName);
    }

    private static String suiInitialMintBlock() {
        return """
                    if (initial > 0) {
                        let minted = coin::mint(&mut treasury_cap, initial, ctx);
                        transfer::public_transfer(minted, owner);
                    };
                """;
    }

    private static String suiMintAuthorityBlock(boolean mintable) {
        String recipient = mintable ? "owner" : "@0x0";
        return """
                    let authority = MintAuthority {
                        id: object::new(ctx),
                        treasury_cap,
                        owner,
                        max_supply,
                    };
                    transfer::public_transfer(authority, %s);
                """.formatted(recipient);
    }

    private static String utf8Hex(String value) {
        return HexFormat.of().formatHex((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private TemplateType parseType(String value) {
        try {
            return TemplateType.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private AccountChainProfile requireContractProfile(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(chain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "chain profile is not enabled: " + chain));
        ChainType chainType;
        try {
            chainType = ChainType.valueOf(profile.getChain());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported chain: " + chain);
        }
        ContractFamily family;
        try {
            family = contractFamily(profile);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contract deployment currently supports EVM, TRON, NEAR, Solana, Aptos, Sui, Polkadot and TON chains only");
        }
        if (family == ContractFamily.EVM && (profile.getChainId() == null || !chainType.isEvm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EVM chain id is not configured");
        }
        if (family == ContractFamily.TRON && chainType != ChainType.TRON) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRON contract deployment requires TRON profile");
        }
        if (family == ContractFamily.NEAR && chainType != ChainType.NEAR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NEAR contract deployment requires NEAR profile");
        }
        if (family == ContractFamily.SOLANA && chainType != ChainType.SOLANA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solana contract deployment requires SOLANA profile");
        }
        if (family == ContractFamily.APTOS && chainType != ChainType.APTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aptos contract deployment requires APTOS profile");
        }
        if (family == ContractFamily.SUI && chainType != ChainType.SUI) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sui contract deployment requires SUI profile");
        }
        if (family == ContractFamily.POLKADOT && chainType != ChainType.DOT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Polkadot contract deployment requires DOT profile");
        }
        if (family == ContractFamily.TON && chainType != ChainType.TON) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TON contract deployment requires TON profile");
        }
        return profile;
    }

    private ContractFamily contractFamily(AccountChainProfile profile) {
        return ContractFamily.parseProfileFamily(profile.getFamily());
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
            if (contractFamily(profile) == ContractFamily.TRON) {
                try (TronTridentClient client = tronClientFactory.create()) {
                    return Optional.of(sunToNative(client.getBalanceSun(address)));
                }
            }
            if (contractFamily(profile) == ContractFamily.NEAR) {
                return Optional.of(yoctoToNear(nearRpcClient.accountBalanceYocto(address)));
            }
            if (contractFamily(profile) == ContractFamily.SOLANA) {
                return Optional.of(lamportsToSol(solanaRpcClient.getBalance(address)));
            }
            if (contractFamily(profile) == ContractFamily.APTOS) {
                return Optional.of(octasToApt(BigInteger.valueOf(
                        aptosRpcClient.coinBalance(address, AptosRpcClient.aptCoinType()))));
            }
            if (contractFamily(profile) == ContractFamily.SUI) {
                return Optional.of(mistToSui(suiRpcClient.balance(address, SuiRpcClient.SUI_COIN_TYPE).toBigInteger()));
            }
            if (contractFamily(profile) == ContractFamily.POLKADOT) {
                return Optional.of(planckToNative(profile, polkadotRuntimeClient.assetHubNativeBalance(address)));
            }
            if (contractFamily(profile) == ContractFamily.TON) {
                return Optional.of(nanoToTon(BigInteger.valueOf(Math.max(0L, tonCenterClient.balance(address)))));
            }
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

    private static List<String> securityNotes(CompiledTemplate template) {
        if (template.family() == ContractFamily.NEAR) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "NEP-141 uses a fixed near-sdk-js template with storage management and FT core methods.",
                        "Initial supply is assigned to the owner account during initialization.",
                        "The contract account is the deployment address itself on NEAR.");
            }
            return List.of(
                    "NEP-171 uses a fixed near-sdk-js NFT template with metadata, enumeration and approval methods.",
                    "Minting is restricted to the owner account configured during initialization.",
                    "The contract account is the deployment address itself on NEAR.");
        }
        if (template.family() == ContractFamily.SUI) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "Sui Coin uses a generated fixed Move template compiled by the backend Sui CLI.",
                        "Minting is routed through a MintAuthority object that enforces max supply.",
                        "The published package id is the deployed contract address.");
            }
            return List.of(
                    "Sui NFT uses a generated fixed Move template with Collection and Nft objects.",
                    "Minting is restricted to the configured owner and enforces max supply.",
                    "The published package id is the deployed contract address.");
        }
        if (template.family() == ContractFamily.SOLANA) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "Solana deployment creates a standard SPL Token mint, not a custom on-chain program.",
                        "Initial supply is minted to the configured owner associated token account.",
                        "Mint authority is transferred to the owner when minting stays enabled, or revoked at deployment.");
            }
            return List.of(
                    "Solana NFT deployment creates a single-supply SPL mint with decimals 0.",
                    "The owner receives the only token in the associated token account.",
                    "Metaplex Token Metadata is not attached in this version.");
        }
        if (template.family() == ContractFamily.APTOS) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "Aptos Coin deployment publishes a fixed Move package compiled by the backend Aptos CLI.",
                        "The deployment address owns the package and mint capability in this version.",
                        "Max supply is checked by the fixed module before owner mint.");
            }
            return List.of(
                    "Aptos single-asset deployment publishes a fixed Move package with a decimals-0 Coin<T>.",
                    "The deployment address receives the only unit during module initialization.",
                    "Aptos Digital Asset metadata is not attached in this version.");
        }
        if (template.family() == ContractFamily.POLKADOT) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "Polkadot deployment creates a standard Asset Hub pallet-assets asset, not uploaded WASM code.",
                        "Initial supply is minted to the deployment address during asset creation.",
                        "Minting is controlled by Asset Hub roles; max supply is wallet-policy metadata, not a runtime-enforced cap.");
            }
            return List.of(
                    "Polkadot single asset deployment creates a decimals-0 Asset Hub asset with supply 1.",
                    "The deployment address receives the only unit during asset creation.",
                    "NFT metadata and pallet-uniques are not attached in this version.");
        }
        if (template.family() == ContractFamily.TON) {
            if (template.type() == TemplateType.ERC20) {
                return List.of(
                        "TON Jetton deployment uses the standard ton4j JettonMinter contract.",
                        "The deployment address is the Jetton admin in this version.",
                        "Initial mint is sent to the deployment address when initial supply is greater than zero.");
            }
            return List.of(
                    "TON NFT deployment uses the standard ton4j NFT Collection contract.",
                    "The deployment address is the collection admin in this version.",
                    "NFT item minting and wallet token-list onboarding are not automatic in this version.");
        }
        String tokenStandard = template.family() == ContractFamily.TRON ? "TRC20" : "ERC20";
        String nftStandard = template.family() == ContractFamily.TRON ? "TRC721" : "ERC721";
        if (template.type() == TemplateType.ERC20) {
            return List.of(
                    tokenStandard + " uses a fixed OpenZeppelin-based template with Ownable, Capped, Pausable, Burnable and Permit.",
                    "Max supply is enforced in the contract; owner mint can be disabled permanently at deployment.",
                    "Owner can pause transfers for emergency handling.");
        }
        return List.of(
                nftStandard + " uses a fixed OpenZeppelin-based template with Ownable, Enumerable, URI storage, Pausable and Burnable.",
                "Max supply is immutable and enforced by the mint function.",
                "Owner can pause transfers for emergency handling.");
    }

    private static List<String> deploymentWarnings(DeploymentPlan plan) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Deployment spends " + plan.profile().getNativeSymbol()
                + " from the contract deployment address; make sure the address has scanned gas balance.");
        warnings.add("The deployed contract will not be added to wallet token configuration automatically.");
        warnings.add(switch (plan.template().family()) {
            case TRON -> "Only TRC20 and TRC721 fixed templates are supported on TRON in this version.";
            case NEAR -> "Only NEP-141 and NEP-171 fixed templates are supported on NEAR in this version.";
            case SOLANA -> "Only SPL Token mint and single-supply SPL NFT mint templates are supported on Solana in this version.";
            case APTOS -> "Only Aptos Coin and single-supply Coin asset fixed Move templates are supported on Aptos in this version.";
            case SUI -> "Only Sui Coin and Sui NFT fixed Move templates are supported on Sui in this version.";
            case POLKADOT -> "Only Asset Hub fungible asset and single-supply asset templates are supported on Polkadot in this version.";
            case TON -> "Only standard Jetton minter and NFT collection templates are supported on TON in this version.";
            default -> "Only ERC20 and ERC721 fixed templates are supported on EVM chains in this version.";
        });
        if (plan.template().family() == ContractFamily.SOLANA && plan.template().type() == TemplateType.ERC20
                && Boolean.TRUE.equals(plan.parameters().parameterView().get("mintable"))) {
            warnings.add("Solana SPL Token Program does not enforce max supply after mint authority is retained by the owner.");
        }
        if (plan.template().family() == ContractFamily.SOLANA && plan.template().type() == TemplateType.ERC721) {
            warnings.add("Solana NFT deployment creates the token mint only; Metaplex metadata is not written in this version.");
        }
        if (plan.template().family() == ContractFamily.APTOS) {
            warnings.add("Aptos package ownership and mint control stay with the deployment address in this version.");
        }
        if (plan.template().family() == ContractFamily.APTOS && plan.template().type() == TemplateType.ERC721) {
            warnings.add("Aptos single-asset deployment uses Coin<T>; Aptos Digital Asset metadata is not written in this version.");
        }
        if (plan.template().family() == ContractFamily.POLKADOT) {
            warnings.add("Polkadot Asset Hub deployment creates runtime assets, not custom smart contract code.");
        }
        if (plan.template().family() == ContractFamily.POLKADOT && plan.template().type() == TemplateType.ERC20
                && Boolean.TRUE.equals(plan.parameters().parameterView().get("mintable"))) {
            warnings.add("Asset Hub does not enforce the configured max supply after the issuer role is retained by the owner.");
        }
        if (plan.template().family() == ContractFamily.POLKADOT && plan.template().type() == TemplateType.ERC721) {
            warnings.add("Polkadot single asset deployment does not attach NFT metadata in this version.");
        }
        if (plan.template().family() == ContractFamily.TON) {
            warnings.add("TON deployment uses standard contracts from ton4j; arbitrary FunC source is not accepted.");
        }
        if (plan.template().family() == ContractFamily.TON && plan.template().type() == TemplateType.ERC20) {
            warnings.add("TON Jetton max supply and mintable flag are recorded as wallet parameters; the standard minter admin remains the deployment address.");
        }
        if (plan.template().family() == ContractFamily.TON && plan.template().type() == TemplateType.ERC721) {
            warnings.add("TON NFT deployment creates the collection contract only; item minting is not automatic in this version.");
        }
        if (plan.template().family() != ContractFamily.POLKADOT
                && plan.template().family() != ContractFamily.TON
                && plan.template().type() == TemplateType.ERC20
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

    private static String normalizeOwner(AccountChainProfile profile, String requestedOwner, String defaultOwner) {
        String value = requestedOwner == null || requestedOwner.isBlank() ? defaultOwner : requestedOwner.trim();
        if ("tron".equalsIgnoreCase(profile.getFamily())) {
            if (value.matches("(?i)^41[0-9a-f]{40}$")) {
                return TronAddressCodec.hexToBase58(value);
            }
            if (!TronAddressCodec.isValidBase58(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid TRON address");
            }
            return value;
        }
        if ("near".equalsIgnoreCase(profile.getFamily())) {
            if (!NearKeyService.isValidAccountId(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid NEAR account id");
            }
            return value;
        }
        if ("solana".equalsIgnoreCase(profile.getFamily())) {
            try {
                return new PublicKey(value).toBase58();
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid Solana address");
            }
        }
        if ("aptos".equalsIgnoreCase(profile.getFamily())) {
            return aptosNormalizeAddress(value);
        }
        if ("sui".equalsIgnoreCase(profile.getFamily())) {
            return suiNormalizeAddress(value);
        }
        if ("polkadot".equalsIgnoreCase(profile.getFamily())) {
            if (!PolkadotKeyService.isValidSs58Address(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "owner address must be a valid Polkadot SS58 address");
            }
            return value;
        }
        if ("ton".equalsIgnoreCase(profile.getFamily())) {
            try {
                return tonFriendly(org.ton.ton4j.address.Address.of(value), profile);
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "owner address must be a valid TON address");
            }
        }
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

    private static void requireSuiU64(BigInteger value, String label) {
        if (value.signum() < 0 || value.compareTo(SUI_U64_MAX) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " must fit the wallet-supported Sui amount range");
        }
    }

    private static long requireSolanaLong(BigInteger value, String label) {
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " must fit the wallet-supported Solana amount range");
        }
        return value.longValueExact();
    }

    private static long requireAptosU64(BigInteger value, String label) {
        if (value.signum() < 0 || value.compareTo(APTOS_U64_MAX) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " must fit the wallet-supported Aptos amount range");
        }
        return value.longValueExact();
    }

    private static BigDecimal weiToNative(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_NATIVE, 18, RoundingMode.DOWN);
    }

    private static BigDecimal sunToNative(long sun) {
        return new BigDecimal(sun).divide(SUN_PER_TRX, 6, RoundingMode.DOWN);
    }

    private static BigDecimal yoctoToNear(BigInteger yocto) {
        return new BigDecimal(yocto == null ? BigInteger.ZERO : yocto)
                .divide(YOCTO_PER_NEAR, 24, RoundingMode.DOWN);
    }

    private static BigDecimal lamportsToSol(long lamports) {
        return lamportsToSol(BigInteger.valueOf(Math.max(0L, lamports)));
    }

    private static BigDecimal lamportsToSol(BigInteger lamports) {
        return new BigDecimal(lamports == null ? BigInteger.ZERO : lamports)
                .divide(LAMPORTS_PER_SOL, 9, RoundingMode.DOWN);
    }

    private static BigDecimal octasToApt(BigInteger octas) {
        return new BigDecimal(octas == null ? BigInteger.ZERO : octas)
                .divide(OCTAS_PER_APT, 8, RoundingMode.DOWN);
    }

    private static BigDecimal mistToSui(BigInteger mist) {
        return new BigDecimal(mist == null ? BigInteger.ZERO : mist)
                .divide(MIST_PER_SUI, 9, RoundingMode.DOWN);
    }

    private static BigDecimal nanoToTon(BigInteger nano) {
        return new BigDecimal(nano == null ? BigInteger.ZERO : nano)
                .divide(NANO_PER_TON, 9, RoundingMode.DOWN);
    }

    private BigDecimal planckToNative(AccountChainProfile profile, BigInteger planck) {
        return new BigDecimal(planck == null ? BigInteger.ZERO : planck)
                .movePointLeft(nativeDecimals(profile))
                .stripTrailingZeros();
    }

    private BigInteger polkadotDeploymentReservePlanck(AccountChainProfile profile) {
        Long configured = profile.getDefaultFee();
        if (configured != null && configured > 0) {
            return BigInteger.valueOf(configured);
        }
        return BigInteger.TEN.pow(nativeDecimals(profile)).divide(BigInteger.valueOf(5L));
    }

    private int nativeDecimals(AccountChainProfile profile) {
        return repository.findAsset(profile.getChain(), profile.getNativeSymbol())
                .map(ChainAsset::getDecimals)
                .filter(value -> value != null && value > 0)
                .orElse(10);
    }

    private BigInteger tonDeploymentReserveNano(AccountChainProfile profile) {
        Long configured = profile.getDefaultFee();
        if (configured != null && configured > 0) {
            return BigInteger.valueOf(configured).max(TON_DEPLOY_FALLBACK_RESERVE_NANO);
        }
        return TON_DEPLOY_FALLBACK_RESERVE_NANO;
    }

    private static String tonFriendly(org.ton.ton4j.address.Address address, AccountChainProfile profile) {
        return address.toString(true, true, true, isTonTestnet(profile));
    }

    private static String tonAddressKey(String address) {
        return org.ton.ton4j.address.Address.of(address).toString(false);
    }

    private static boolean isTonTestnet(AccountChainProfile profile) {
        String network = profile == null ? "" : profile.getNetwork();
        return network != null && network.toLowerCase(Locale.ROOT).contains("test");
    }

    private static String suiNormalizeAddress(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("0x")) {
            value = value.substring(2);
        }
        if (value.isBlank() || value.length() > 64 || !value.matches("^[0-9a-f]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid Sui address");
        }
        return "0x" + "0".repeat(64 - value.length()) + value;
    }

    private static String aptosNormalizeAddress(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("0x")) {
            value = value.substring(2);
        }
        if (value.isBlank() || value.length() > 64 || !value.matches("^[0-9a-f]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner address must be a valid Aptos address");
        }
        return "0x" + "0".repeat(64 - value.length()) + value;
    }

    private static FeeQuote evmFeeQuote(BigInteger gasPriceWei, BigInteger gasLimit,
                                        boolean fallback, String fallbackReason) {
        return new FeeQuote(gasPriceWei, gasLimit, fallback, fallbackReason,
                weiToNative(gasPriceWei.multiply(gasLimit)), "native", "wei");
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

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unable to serialize contract parameters");
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
                                  String deployData,
                                  String sourceCode,
                                  String bytecodeHash,
                                  RenderedAptosPackage aptosMovePackage,
                                  CompiledAptosPackage aptosCompiledPackage,
                                  RenderedMovePackage suiMovePackage,
                                  CompiledMovePackage suiCompiledPackage) {
    }

    private record ContractParameters(String name,
                                      String symbol,
                                      String ownerAddress,
                                      Map<String, Object> parameterView,
                                      List<Map<String, Object>> constructorView,
                                      List<Type> evmArguments,
                                      List<org.tron.trident.abi.datatypes.Type<?>> tronArguments,
                                      String nearArgumentsJson) {
    }

    private record FeeQuote(BigInteger gasPriceWei,
                            BigInteger gasLimit,
                            boolean fallback,
                            String fallbackReason,
                            BigDecimal feeLimitNative,
                            String feeUnit,
                            String gasPriceUnit) {
        private Map<String, Object> toPayload() {
            Map<String, Object> payload = orderedMap();
            payload.put("gasPriceWei", gasPriceWei.toString());
            payload.put("gasLimit", gasLimit.toString());
            payload.put("feeLimit", feeLimitNative().stripTrailingZeros().toPlainString());
            payload.put("feeUnit", feeUnit);
            payload.put("gasPriceUnit", gasPriceUnit);
            payload.put("fallback", fallback);
            payload.put("fallbackReason", fallbackReason);
            return payload;
        }
    }

    private record BroadcastResult(String txHash, Long nonce, String contractAddress) {
    }
}
