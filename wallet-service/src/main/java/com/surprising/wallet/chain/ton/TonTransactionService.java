package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.smartcontract.SendMode;
import org.ton.ton4j.smartcontract.token.ft.JettonWalletV2;
import org.ton.ton4j.smartcontract.types.WalletV4R2Config;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.StateInit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;

/**
 * TON 交易服务，负责构建、签名和发送 TON Wallet V4R2 外部消息。
 *
 * <p>基于 ton4j 库实现，支持：
 * <ul>
 *   <li>原生 TON 转账（WalletV4R2 internal message，支持 text comment）</li>
 *   <li>Jetton 代币转账（TEP-74 jetton_transfer，通过 Jetton Wallet 合约）</li>
 *   <li>钱包部署（prepareDeployMsg）</li>
 *   <li>通用合约调用（prepareContractCall，支持 StateInit 和自定义 body）</li>
 *   <li>提现流程（seqno 预留、余额冻结、签名广播、外部消息确认）</li>
 *   <li>归集流程（原生币和 Jetton 归集到热钱包）</li>
 * </ul>
 *
 * <p>每条消息设置 validUntil = 当前时间 + 15 分钟，使用 seqno 递增防止重放。
 * 消息通过 BOC（Bag of Cells）序列化后经由 {@link TonCenterClient#sendBoc} 广播。
 * 确认时通过查询外部消息哈希在交易历史中的出现来判断消息是否已被处理。
 *
 * @see TonCenterClient
 * @see TonKeyService
 * @see WalletV4R2
 */
@Service
@RequiredArgsConstructor
public
class TonTransactionService {

    /** TON 链标识 */
    private static final String CHAIN = "TON";

    /** Jetton 转账 forward_ton 金额（nanoTON），用于通知接收方 */
    private static final BigInteger JETTON_FORWARD_TON = BigInteger.valueOf(10_000_000L);

    /** Jetton 转账附加的 TON Gas 费（nanoTON） */
    private static final BigInteger JETTON_GAS_TON = BigInteger.valueOf(70_000_000L);

    /** 消息有效期（秒），过期消息将被拒绝 */
    private static final long MESSAGE_VALIDITY_SECONDS = 15 * 60L;

    /** 确认扫描时的交易查询上限 */
    private static final int CONFIRMATION_SCAN_LIMIT = 100;

    /** TON Center RPC 客户端 */
    private final TonCenterClient rpc;

    /** 密钥服务 */
    private final TonKeyService keyService;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选注入） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 准备原生 TON 转账消息（通过派生索引）。
     *
     * @param derivationIndex 派生索引
     * @param toAddress       接收方地址
     * @param amountNano      转账金额（nanoTON）
     * @param comment         可选文本备注
     * @return 准备好的转账对象
     */

    public PreparedTransfer prepareNative(long derivationIndex, String toAddress,
                                          BigInteger amountNano, String comment) {
        return prepareNative(0L, 0, derivationIndex, toAddress, amountNano, comment);
    }

    /**
     * 准备原生 TON 转账消息（通过地址记录）。
     *
     * @param from       发送方地址记录
     * @param toAddress  接收方地址
     * @param amountNano 转账金额（nanoTON）
     * @param comment    可选文本备注
     * @return 准备好的转账对象
     */
    public PreparedTransfer prepareNative(ChainAddressRecord from, String toAddress,
                                          BigInteger amountNano, String comment) {
        return prepareNative(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                toAddress, amountNano, comment);
    }

    private PreparedTransfer prepareNative(long userId, int biz, long derivationIndex,
                                           String toAddress, BigInteger amountNano, String comment) {
        WalletV4R2 wallet = keyService.wallet(userId, biz, derivationIndex);
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .validUntil(Instant.now().plusSeconds(MESSAGE_VALIDITY_SECONDS).getEpochSecond())
                .bounce(false)
                .destination(Address.of(toAddress))
                .amount(amountNano)
                .comment(comment)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }

    public PreparedTransfer prepareJetton(long derivationIndex, String sourceJettonWallet,
                                          String destinationOwner, BigInteger tokenAmount,
                                          String responseAddress, String comment) {
        return prepareJetton(0L, 0, derivationIndex, sourceJettonWallet,
                destinationOwner, tokenAmount, responseAddress, comment);
    }

    /**
     * 准备 Jetton 代币转账消息（通过地址记录）。
     *
     * @param from               发送方地址记录
     * @param sourceJettonWallet 源 Jetton Wallet 地址
     * @param destinationOwner   目标所有者地址
     * @param tokenAmount        转账金额（原子单位）
     * @param responseAddress    超额退款地址
     * @param comment            可选文本备注
     * @return 准备好的转账对象
     */
    public PreparedTransfer prepareJetton(ChainAddressRecord from, String sourceJettonWallet,
                                          String destinationOwner, BigInteger tokenAmount,
                                          String responseAddress, String comment) {
        return prepareJetton(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                sourceJettonWallet, destinationOwner, tokenAmount, responseAddress, comment);
    }

    private PreparedTransfer prepareJetton(long userId, int biz, long derivationIndex, String sourceJettonWallet,
                                           String destinationOwner, BigInteger tokenAmount,
                                           String responseAddress, String comment) {
        WalletV4R2 wallet = keyService.wallet(userId, biz, derivationIndex);
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        Cell forwardPayload = comment == null || comment.isBlank()
                ? null : MsgUtils.createTextMessageBody(comment);
        Cell jettonBody = JettonWalletV2.createTransferBody(
                System.nanoTime(), tokenAmount, Address.of(destinationOwner), Address.of(responseAddress),
                null, JETTON_FORWARD_TON, forwardPayload);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .validUntil(Instant.now().plusSeconds(MESSAGE_VALIDITY_SECONDS).getEpochSecond())
                .bounce(true)
                .destination(Address.of(sourceJettonWallet))
                .amount(JETTON_GAS_TON)
                .body(jettonBody)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }
    public PreparedTransfer prepareWalletDeploy(long derivationIndex) {
        return prepareWalletDeploy(keyService.wallet(derivationIndex));
    }
    public PreparedTransfer prepareWalletDeploy(ChainAddressRecord from) {
        return prepareWalletDeploy(keyService.wallet(
                from.getUserId(), from.getBiz(), from.getAddressIndex()));
    }
    private PreparedTransfer prepareWalletDeploy(WalletV4R2 wallet) {
        Message message = wallet.prepareDeployMsg();
        byte[] boc = message.toCell().toBoc(false);
        return new PreparedTransfer(0, boc,
                java.util.Base64.getEncoder().encodeToString(boc),
                java.util.HexFormat.of().formatHex(message.toCell().hash()));
    }

    public PreparedTransfer prepareContractCall(long derivationIndex, String destination,
                                                BigInteger amountNano, StateInit stateInit,
                                                Cell body, boolean bounce) {
        WalletV4R2 wallet = keyService.wallet(derivationIndex);
        return prepareContractCall(wallet, destination, amountNano, stateInit, body, bounce);
    }

    public PreparedTransfer prepareContractCall(ChainAddressRecord from, String destination,
                                                BigInteger amountNano, StateInit stateInit,
                                                Cell body, boolean bounce) {
        WalletV4R2 wallet = keyService.wallet(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return prepareContractCall(wallet, destination, amountNano, stateInit, body, bounce);
    }

    private PreparedTransfer prepareContractCall(WalletV4R2 wallet, String destination,
                                                 BigInteger amountNano, StateInit stateInit,
                                                 Cell body, boolean bounce) {
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .validUntil(Instant.now().plusSeconds(MESSAGE_VALIDITY_SECONDS).getEpochSecond())
                .bounce(bounce)
                .destination(Address.of(destination))
                .amount(amountNano)
                .stateInit(stateInit)
                .body(body)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }
    /**
     * 广播准备好的 BOC 消息到 TON 网络。
     *
     * @param transfer 准备好的转账对象
     * @return 消息哈希
     */
    public String broadcast(PreparedTransfer transfer) {
        return rpc.sendBoc(transfer.boc());
    }

    /**
     * 广播消息并记录交易到数据库。
     *
     * <p>广播失败时使用 messageHashHex 作为 fallback。
     *
     * @param transfer 准备好的转账对象
     * @param from     发送方地址
     * @param to       接收方地址
     * @param symbol   资产符号
     * @param master   Jetton Master 地址（原生币为 null）
     * @param amount   交易金额
     * @return 消息哈希
     */
    public String broadcastAndRecord(PreparedTransfer transfer, String from, String to,
                                     String symbol, String master, BigDecimal amount) {
        String hash = broadcast(transfer);
        if (hash == null || hash.isBlank()) {
            hash = transfer.messageHashHex();
        }
        long fee = master == null ? profile().getDefaultFee() : JETTON_GAS_TON.longValue();
        record(hash, from, to, symbol, master, amount, fee, null, "SENT", transfer.bocBase64());
        return hash;
    }
    /**
     * 确认已发送的外部消息是否被链上处理。
     *
     * <p>如果未找到，自动重广播原始 BOC；如果找到但没有 out_msgs，抛出异常（消息被消耗但无转账）。
     *
     * @param messageHash   外部消息哈希
     * @param senderAddress 发送方地址
     * @return true 表示消息已成功处理
     */
    public boolean confirmSentMessage(String messageHash, String senderAddress) {
        Optional<com.fasterxml.jackson.databind.JsonNode> transaction = rpc.findExternalMessageTransaction(
                senderAddress, messageHash, CONFIRMATION_SCAN_LIMIT);
        if (transaction.isEmpty()) {
            rebroadcast(messageHash);
            return false;
        }
        if (!transaction.get().path("out_msgs").isArray()
                || transaction.get().path("out_msgs").isEmpty()) {
            throw new IllegalStateException("TON external message was processed without an outgoing transfer: "
                    + messageHash);
        }
        repository.markTonTransactionConfirmed(CHAIN, messageHash);
        repository.synchronizeAccountSequence(CHAIN, senderAddress, rpc.seqno(senderAddress));
        return true;
    }
    private void rebroadcast(String messageHash) {
        repository.findTonTransactionRawPayload(CHAIN, messageHash).ifPresent(rawPayload -> {
            try {
                rpc.sendBoc(Base64.getDecoder().decode(rawPayload));
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (!message.contains("duplicate msg_seqno") && !message.contains("too old seqno")) {
                    throw e;
                }
            }
        });
    }

    /**
     * 执行 TON 原生币提现（冻结、签名、广播、状态追踪全流程）。
     *
     * @param tenantId  租户 ID
     * @param orderNo   提现订单号
     * @param userId    用户 ID
     * @param from      源地址记录
     * @param toAddress 目标地址
     * @param amount    提现金额（TON）
     * @param memo      可选备注
     * @return 消息哈希
     */
    public String withdrawNative(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "ton withdrawNative");
        Optional<String> existing = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeNano = profile().getDefaultFee();
        BigDecimal fee = displayAmount(feeNano, 9);
        if (repository.createTenantWithdrawalOrder(tenantId, orderNo, userId, CHAIN, "TON",
                from.getAddress(), from.getAccountId(), toAddress, amount, fee) == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("TON withdrawal already claimed"));
        }
        BigDecimal debit = amount.add(fee);
        if (!repository.freezeLedgerBalance(tenantId, CHAIN, "TON", from.getAccountId(), debit)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient TON ledger balance");
            throw new IllegalStateException("insufficient TON ledger balance");
        }
        repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("TON withdrawal is not signable: " + orderNo);
            }
            PreparedTransfer prepared = prepareNative(from, toAddress, atomicAmount(amount, 9), memo);
            String hash = broadcast(prepared);
            if (repository.markWithdrawalSent(tenantId, CHAIN, orderNo, from.getAddress(), hash) != 1) {
                throw new IllegalStateException("TON withdrawal state changed before SENT: " + orderNo);
            }
            record(hash, from.getAddress(), toAddress, "TON", null, amount, feeNano,
                    null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 执行 Jetton 代币提现。
     *
     * @param tenantId         租户 ID
     * @param orderNo          提现订单号
     * @param userId           用户 ID
     * @param from             源地址记录
     * @param jettonMaster     Jetton Master 合约地址
     * @param destinationOwner 目标所有者地址
     * @param amount           提现金额（代币单位）
     * @param memo             可选备注
     * @return 消息哈希
     */
    public String withdrawJetton(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                 String jettonMaster, String destinationOwner,
                                 BigDecimal amount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "ton withdrawJetton");
        Optional<String> existing = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, jettonMaster)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Jetton " + jettonMaster));
        if (repository.createTenantWithdrawalOrder(tenantId, orderNo, userId, CHAIN, token.getSymbol(),
                from.getOwnerAddress(), from.getAccountId(), destinationOwner,
                amount, BigDecimal.ZERO) == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Jetton withdrawal already claimed"));
        }
        if (!repository.freezeLedgerBalance(
                tenantId, CHAIN, token.getSymbol(), from.getAccountId(), amount)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null,
                    "insufficient Jetton ledger balance");
            throw new IllegalStateException("insufficient Jetton ledger balance");
        }
        repository.updateWithdrawalStatus(
                tenantId, CHAIN, orderNo, "FROZEN", from.getOwnerAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getOwnerAddress()) != 1) {
                throw new IllegalStateException("Jetton withdrawal is not signable: " + orderNo);
            }
            PreparedTransfer prepared = prepareJetton(from, from.getAddress(),
                    destinationOwner, atomicAmount(amount, token.getDecimals()), from.getOwnerAddress(), memo);
            String hash = broadcast(prepared);
            if (repository.markWithdrawalSent(
                    tenantId, CHAIN, orderNo, from.getOwnerAddress(), hash) != 1) {
                throw new IllegalStateException("Jetton withdrawal state changed before SENT: " + orderNo);
            }
            record(hash, from.getAddress(), destinationOwner, token.getSymbol(), jettonMaster,
                    amount, JETTON_GAS_TON.longValue(), null,
                    "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getOwnerAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 确认提现交易（等待外部消息被链上处理并清算）。
     *
     * @param tenantId    租户 ID
     * @param orderNo     提现订单号
     * @param symbol      资产符号
     * @param accountId   账户 ID
     * @param debitAmount 扣款金额
     * @return true 表示确认并结算成功
     */
    public boolean confirmWithdrawal(UUID tenantId, String orderNo, String symbol, String accountId,
                                     BigDecimal debitAmount) {
        String hash = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        if (!confirmSentMessage(hash, accountId)) {
            return false;
        }
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, hash, symbol, accountId, debitAmount)) {
            repository.markTonTransactionConfirmed(CHAIN, hash);
            return true;
        }
        return false;
    }

    /**
     * 归集原生 TON 到热钱包。
     *
     * @param tenantId      租户 ID
     * @param collectionNo  归集编号
     * @param from          源地址记录
     * @param hotAddress    热钱包地址
     * @param amount        归集金额（TON）
     * @param memo          可选备注
     * @return 消息哈希
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "ton collectNative");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeNano = profile().getDefaultFee();
        BigDecimal fee = displayAmount(feeNano, 9);
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("TON collection is not retryable"));
        }
        try {
            PreparedTransfer prepared = prepareNative(from, hotAddress, atomicAmount(amount, 9), memo);
            String hash = broadcast(prepared);
            repository.updateCollectionStatus(
                    tenantId, CHAIN, collectionNo, "SENT", hash, null, prepared.bocBase64());
            record(hash, from.getAddress(), hotAddress, "TON", null, amount, feeNano,
                    null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 归集 Jetton 代币到热钱包。
     *
     * @param tenantId        租户 ID
     * @param collectionNo    归集编号
     * @param from            源地址记录
     * @param jettonMaster    Jetton Master 合约地址
     * @param hotOwnerAddress 热钱包所有者地址
     * @param amount          归集金额（代币单位）
     * @param memo            可选备注
     * @return 消息哈希
     */
    public String collectJetton(java.util.UUID tenantId, String collectionNo,
                                ChainAddressRecord from, String jettonMaster,
                                String hotOwnerAddress, BigDecimal amount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "ton collectJetton");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, jettonMaster)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Jetton " + jettonMaster));
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("TON Jetton collection is not retryable"));
        }
        try {
            PreparedTransfer prepared = prepareJetton(from, from.getAddress(),
                    hotOwnerAddress, atomicAmount(amount, token.getDecimals()), from.getOwnerAddress(), memo);
            String hash = broadcast(prepared);
            repository.updateCollectionStatus(
                    tenantId, CHAIN, collectionNo, "SENT", hash, null, prepared.bocBase64());
            record(hash, from.getAddress(), hotOwnerAddress, token.getSymbol(), jettonMaster,
                    amount, JETTON_GAS_TON.longValue(), null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    /**
     * 确认归集交易。
     *
     * @param tenantId      租户 ID
     * @param collectionNo  归集编号
     * @param senderAddress 发送方地址
     * @return true 表示确认成功
     */
    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo, String senderAddress) {
        String hash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        if (!confirmSentMessage(hash, senderAddress)) {
            return false;
        }
        if (repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, hash) == 1) {
            return true;
        }
        return false;
    }
    private PreparedTransfer prepare(WalletV4R2 wallet, WalletV4R2Config config, long seqno) {
        Message message = wallet.prepareExternalMsg(config);
        byte[] boc = message.toCell().toBoc(false);
        return new PreparedTransfer(seqno, boc,
                java.util.Base64.getEncoder().encodeToString(boc),
                java.util.HexFormat.of().formatHex(message.toCell().hash()));
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private static BigInteger atomicAmount(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).toBigIntegerExact();
    }
    private static BigDecimal displayAmount(long atomicAmount, int decimals) {
        return BigDecimal.valueOf(atomicAmount).movePointLeft(decimals);
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private void record(String hash, String from, String to, String symbol, String master,
                        BigDecimal amount, long fee, BigInteger lt, String status, String rawPayload) {
        repository.recordTonTransaction(TonTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(hash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .jettonMaster(master)
                .amount(amount)
                .feeNano(fee)
                .logicalTime(lt)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }
    private String friendly(Address address, boolean bounceable) {
        boolean testnet = profile().getNetwork().toLowerCase(java.util.Locale.ROOT).contains("test");
        return address.toString(true, true, bounceable, testnet);
    }
    /**
     * 准备好的 TON 转账对象。
     *
     * @param seqno           使用的 seqno
     * @param boc             BOC 字节数组
     * @param bocBase64       BOC 的 Base64 编码（用于广播）
     * @param messageHashHex  外部消息哈希（Hex 格式，用于确认追踪）
     */
    public record PreparedTransfer(long seqno, byte[] boc, String bocBase64, String messageHashHex) {
    }
}
