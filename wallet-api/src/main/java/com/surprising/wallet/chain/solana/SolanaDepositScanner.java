package com.surprising.wallet.chain.solana;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.chain.model.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Solana 链充值扫描器，通过 RPC 获取签名列表并解析交易指令识别充值。
 *
 * <p>扫描策略：对每个存款地址调用 {@code getSignaturesForAddress} 获取最新交易签名，
 * 然后通过 {@code getTransaction} 解析交易详情。通过展开外层和内联指令
 * （{@code innerInstructions}）匹配 SOL 原生转账（{@code system-program:transfer}）
 * 和 SPL Token 转账（{@code spl-token:transfer/transferChecked}）。</p>
 *
 * <p>自动发现 Associated Token Account（ATA）作为代币存款地址，
 * 无需提前在数据库中注册。</p>
 *
 * @see SolanaRpcClient
 * @see SolanaAddressService
 */
@Service
@RequiredArgsConstructor
public
class SolanaDepositScanner {

    /** 链标识 */
    private static final String CHAIN = "SOLANA";

    /** 扫描器名称 */
    private static final String SCANNER = "solana-signature-scanner";

    /** SOL 小数位数 */
    private static final int SOL_DECIMALS = 9;

    /** Solana RPC 客户端 */
    private final SolanaRpcClient rpc;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /** 地址服务（可选，用于 ATA 派生） */
    @Autowired(required = false)
    private SolanaAddressService addressService;

    /**
     * 执行一次完整的充值扫描并触发入账。
     *
     * @return 发现的充值事件列表
     */
    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "solana scanAndCredit");
        AccountChainProfile profile = profile();
        long currentSlot = rpc.getSlot();
        List<DepositEvent> events = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Set<String> platformAddresses = platformAddresses();

        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, "SOL")) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                scanAddress(address, null, profile, currentSlot, processed, platformAddresses, events);
            }
        }
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            for (ChainAddressRecord address : tokenScanAddresses(token)) {
                if ("DEPOSIT".equals(address.getWalletRole())) {
                    scanAddress(address, token, profile, currentSlot, processed, platformAddresses, events);
                }
            }
        }
        refreshPendingDeposits(profile, currentSlot);
        long safeSlot = Math.max(0, currentSlot - profile.getDepositConfirmations() + 1L);
        repository.updateScanHeight(CHAIN, SCANNER, currentSlot, safeSlot);
        return events;
    }

    /**
     * 扫描或观察 {@code scanAddress} 对应的链上状态，并转换为业务可用结果。
     */
    private void scanAddress(ChainAddressRecord tracked, TokenDefinition token, AccountChainProfile profile,
                             long currentSlot, Set<String> processed, Set<String> platformAddresses,
                             List<DepositEvent> events) {
        ArrayNode signatures = rpc.getSignaturesForAddress(tracked.getAddress(), scanLimit(profile));
        for (JsonNode signatureInfo : signatures) {
            if (!signatureInfo.path("err").isNull() && !signatureInfo.path("err").isMissingNode()) {
                continue;
            }
            String signature = signatureInfo.path("signature").asText();
            if (!processed.add(signature + ":" + tracked.getAddress())) {
                continue;
            }
            JsonNode transaction = rpc.getTransaction(signature);
            if (transaction == null || transaction.isNull()) {
                continue;
            }
            long slot = transaction.path("slot").asLong();
            int confirmations = (int) Math.min(Integer.MAX_VALUE, Math.max(1, currentSlot - slot + 1));
            List<JsonNode> instructions = flattenInstructions(transaction);
            for (int instructionIndex = 0; instructionIndex < instructions.size(); instructionIndex++) {
                JsonNode instruction = instructions.get(instructionIndex);
                JsonNode parsed = instruction.path("parsed");
                String type = parsed.path("type").asText();
                JsonNode info = parsed.path("info");
                DepositEvent event = token == null
                        ? nativeDeposit(signature, tracked, slot, confirmations, transaction, type, info)
                        : tokenDeposit(signature, tracked, token, slot, confirmations, transaction, type, info);
                if (event == null || platformAddresses.contains(event.fromAddress())) {
                    continue;
                }
                repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                        .chain(CHAIN)
                        .signature(signature)
                        .fromAddress(event.fromAddress())
                        .toAddress(event.toAddress())
                        .assetSymbol(event.assetSymbol())
                        .mintAddress(event.tokenAddress())
                        .amount(event.amount())
                        .feeLamports(transaction.path("meta").path("fee").asLong())
                        .slot(slot)
                        .confirmations(confirmations)
                        .status(confirmations >= profile.getDepositConfirmations() ? "CONFIRMED" : "CONFIRMING")
                        .rawPayload(transaction.toString())
                        .build());
                repository.recordAndCreditDeposit(event, instructionIndex,
                        profile.getDepositConfirmations(), tracked.getAccountId());
                events.add(event);
            }
        }
    }
    /**
     * 编码 {@code tokenScanAddresses} 对应的数据，生成链上或接口所需的表示。
     */
    private List<ChainAddressRecord> tokenScanAddresses(TokenDefinition token) {
        List<ChainAddressRecord> addresses = new ArrayList<>(repository.listChainAddresses(CHAIN, token.getSymbol()));
        if (addressService == null || !StringUtils.hasText(token.getContractAddress())) {
            return addresses;
        }
        Set<String> existing = new HashSet<>();
        for (ChainAddressRecord address : addresses) {
            existing.add(normalize(address.getAddress()));
        }
        for (ChainAddressRecord owner : repository.listChainAddresses(CHAIN, "SOL")) {
            if (!"DEPOSIT".equals(owner.getWalletRole())) {
                continue;
            }
            String ownerAddress = StringUtils.hasText(owner.getOwnerAddress())
                    ? owner.getOwnerAddress()
                    : owner.getAddress();
            String ata = addressService.associatedTokenAddress(ownerAddress, token.getContractAddress());
            if (!existing.add(normalize(ata))) {
                continue;
            }
            addresses.add(ChainAddressRecord.builder()
                    .chain(CHAIN)
                    .assetSymbol(token.getSymbol())
                    .accountId(owner.getAccountId())
                    .userId(owner.getUserId())
                    .biz(owner.getBiz())
                    .addressIndex(owner.getAddressIndex())
                    .address(ata)
                    .ownerAddress(ownerAddress)
                    .derivationPath(owner.getDerivationPath())
                    .walletRole(owner.getWalletRole())
                    .enabled(owner.getEnabled())
                    .build());
        }
        return addresses;
    }
    /**
     * 执行 {@code platformAddresses} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Set<String> platformAddresses() {
        Set<String> addresses = new HashSet<>();
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            if (StringUtils.hasText(tracked.getAddress())) {
                addresses.add(tracked.getAddress());
            }
            if (StringUtils.hasText(tracked.getOwnerAddress())) {
                addresses.add(tracked.getOwnerAddress());
            }
        }
        return addresses;
    }

    /**
     * 执行 {@code nativeDeposit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private DepositEvent nativeDeposit(String signature, ChainAddressRecord tracked, long slot, int confirmations,
                                       JsonNode transaction, String type, JsonNode info) {
        if (!"transfer".equals(type) || !tracked.getAddress().equals(info.path("destination").asText())
                || !info.has("lamports")) {
            return null;
        }
        BigDecimal displayAmount = new BigDecimal(info.path("lamports").asText()).movePointLeft(SOL_DECIMALS);
        return new DepositEvent(ChainType.SOLANA, "SOL", signature, info.path("source").asText(),
                tracked.getAddress(), displayAmount, slot, signature, confirmations,
                null, transaction.toString());
    }

    /**
     * 编码 {@code tokenDeposit} 对应的数据，生成链上或接口所需的表示。
     */
    private DepositEvent tokenDeposit(String signature, ChainAddressRecord tracked, TokenDefinition token,
                                      long slot, int confirmations, JsonNode transaction,
                                      String type, JsonNode info) {
        if ((!"transfer".equals(type) && !"transferChecked".equals(type))
                || !tracked.getAddress().equals(info.path("destination").asText())) {
            return null;
        }
        if (StringUtils.hasText(token.getContractAddress())
                && info.hasNonNull("mint")
                && !token.getContractAddress().equals(info.path("mint").asText())) {
            return null;
        }
        String amount = info.has("amount")
                ? info.path("amount").asText()
                : info.path("tokenAmount").path("amount").asText();
        if (amount.isBlank()) {
            return null;
        }
        BigDecimal displayAmount = new BigDecimal(amount).movePointLeft(token.getDecimals());
        return new DepositEvent(ChainType.SOLANA, token.getSymbol(), signature,
                info.path("source").asText(), tracked.getAddress(), displayAmount, slot, signature, confirmations,
                token.getContractAddress(), transaction.toString());
    }
    /**
     * 执行 {@code flattenInstructions} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private List<JsonNode> flattenInstructions(JsonNode transaction) {
        List<JsonNode> instructions = new ArrayList<>();
        JsonNode outer = transaction.path("transaction").path("message").path("instructions");
        outer.forEach(instructions::add);
        JsonNode innerGroups = transaction.path("meta").path("innerInstructions");
        for (JsonNode group : innerGroups) {
            group.path("instructions").forEach(instructions::add);
        }
        return instructions;
    }
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    /**
     * 写入或更新 {@code refreshPendingDeposits} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private void refreshPendingDeposits(AccountChainProfile profile, long currentSlot) {
        int requiredConfirmations = profile.getDepositConfirmations();
        for (var pending : repository.listPendingDeposits(CHAIN, requiredConfirmations, 500)) {
            JsonNode transaction = rpc.getTransaction(pending.txHash());
            if (transaction == null || transaction.isNull()) {
                continue;
            }
            long slot = transaction.path("slot").asLong(pending.blockHeight());
            int confirmations = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, currentSlot - slot + 1L));
            if (confirmations <= pending.confirmations()) {
                continue;
            }
            DepositEvent event = new DepositEvent(
                    ChainType.SOLANA,
                    pending.assetSymbol(),
                    pending.txHash(),
                    pending.fromAddress(),
                    pending.toAddress(),
                    pending.amount(),
                    slot,
                    pending.blockHash(),
                    confirmations,
                    pending.contractAddress(),
                    transaction.toString());
            repository.recordAndCreditDeposit(
                    event, pending.logIndex(), requiredConfirmations, pending.accountId());
        }
    }
    /**
     * 扫描或观察 {@code scanLimit} 对应的链上状态，并转换为业务可用结果。
     */
    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 100 : batchSize;
    }
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private String normalize(String address) {
        return address == null ? "" : address;
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
