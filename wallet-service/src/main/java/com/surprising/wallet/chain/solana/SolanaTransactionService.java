package com.surprising.wallet.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.MemoProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Solana 交易服务，负责构建、签名和发送 Solana 交易。
 *
 * <p>基于 solanaj 库实现，支持：
 * <ul>
 *   <li>原生 SOL 转账（SystemProgram.transfer）</li>
 *   <li>SPL Token 转账（TokenProgram.transferChecked，通过 ATA 账户）</li>
 *   <li>SPL Mint 部署（创建新代币）</li>
 *   <li>提现流程（冻结余额、签名广播、确认结算）</li>
 *   <li>归集流程（原生币和代币归集到热钱包）</li>
 *   <li>ATA 账户自动创建（AssociatedTokenProgram.createIdempotent）</li>
 * </ul>
 *
 * <p>每笔交易使用最新的 recent blockhash，交易签名通过 Ed25519 密钥对完成。
 * Solana 精度为 9 位小数（1 SOL = 1,000,000,000 lamports）。
 *
 * @see SolanaRpcClient
 * @see SolanaKeyService
 * @see SolanaAddressService
 */
@Service
@RequiredArgsConstructor
public
class SolanaTransactionService {

    /** Solana 链标识 */
    private static final String CHAIN = "SOLANA";

    /** SPL Mint 账户数据长度（字节） */
    private static final int SPL_MINT_ACCOUNT_LENGTH = 82;

    /** RPC 客户端 */
    private final SolanaRpcClient rpc;

    /** 密钥服务 */
    private final SolanaKeyService keyService;

    /** 地址服务 */
    private final SolanaAddressService addressService;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选注入） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 发送 SOL 原生转账（通过派生索引）。
     *
     * @param derivationIndex 派生索引
     * @param toAddress       接收方地址
     * @param lamports        转账金额（lamports）
     * @return 交易签名
     */
    public String sendNative(long derivationIndex, String toAddress, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        Account sender = keyService.account(derivationIndex);
        return sendNative(sender, toAddress, lamports);
    }
    public String sendNative(ChainAddressRecord from, String toAddress, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        Account sender = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return sendNative(sender, toAddress, lamports);
    }
    private String sendNative(Account sender, String toAddress, long lamports) {
        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.transfer(
                        sender.getPublicKey(), new PublicKey(toAddress), lamports));
        return signAndSend(transaction, List.of(sender));
    }

    public String sendToken(long derivationIndex, String mintAddress, String toOwnerAddress,
                            long atomicAmount, int decimals) {
        if (atomicAmount <= 0) {
            throw new IllegalArgumentException("token amount must be positive");
        }
        Account sender = keyService.account(derivationIndex);
        return sendToken(sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    public String sendToken(ChainAddressRecord from, String mintAddress, String toOwnerAddress,
                            long atomicAmount, int decimals) {
        if (atomicAmount <= 0) {
            throw new IllegalArgumentException("token amount must be positive");
        }
        Account sender = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return sendToken(sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    public String sendTokenAmount(ChainAddressRecord from, String mintAddress, String toOwnerAddress,
                                  BigDecimal amount, int decimals) {
        ChainAddressRecord hot = defaultHotFeePayer(
                Objects.requireNonNull(from.getTenantId(), "source tenantId is required"));
        Account sourceOwner = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        Account feePayer = keyService.account(hot.getUserId(), hot.getBiz(), hot.getAddressIndex());
        return sendTokenWithFeePayer(sourceOwner, feePayer, mintAddress, toOwnerAddress,
                toAtomicAmount(amount, decimals), decimals);
    }

    private String sendToken(Account sender, String mintAddress, String toOwnerAddress,
                             long atomicAmount, int decimals) {
        return sendTokenWithFeePayer(sender, sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    /**
     * 部署一个新的 SPL Token Mint。
     *
     * <p>执行以下指令序列：创建 Mint 账户、初始化 Mint、创建 Owner 的 ATA、
     * 可选 Mint 初始供应量、设置（或移除）Mint Authority、冻结 Freeze Authority。
     *
     * @param payerRecord          手续费支付者地址记录
     * @param ownerAddress         Token 所有者地址
     * @param decimals             Token 小数位数（0-9）
     * @param initialAtomicAmount  初始供应量（原子单位，0 表示无）
     * @param retainMintAuthority  是否保留增发权限给 owner
     * @param memo                 可选备注
     * @return 部署结果（含签名、Mint 地址、Owner ATA 地址）
     */
    public DeploySplMintResult deploySplMint(ChainAddressRecord payerRecord,
                                             String ownerAddress,
                                             int decimals,
                                             long initialAtomicAmount,
                                             boolean retainMintAuthority,
                                             String memo) {
        if (decimals < 0 || decimals > 9) {
            throw new IllegalArgumentException("Solana SPL decimals must be between 0 and 9");
        }
        if (initialAtomicAmount < 0) {
            throw new IllegalArgumentException("Solana initial supply must be non-negative");
        }
        Account payer = keyService.account(payerRecord.getUserId(), payerRecord.getBiz(), payerRecord.getAddressIndex());
        Account mint = new Account();
        PublicKey owner = new PublicKey(ownerAddress);
        PublicKey ownerAta = new PublicKey(addressService.associatedTokenAddress(ownerAddress, mint.getPublicKeyBase58()));
        long rent = rpc.minimumBalanceForRentExemption(SPL_MINT_ACCOUNT_LENGTH);

        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.createAccount(
                        payer.getPublicKey(), mint.getPublicKey(), rent, SPL_MINT_ACCOUNT_LENGTH, TokenProgram.PROGRAM_ID))
                .addInstruction(TokenProgram.initializeMint(
                        mint.getPublicKey(), decimals, payer.getPublicKey(), payer.getPublicKey()))
                .addInstruction(AssociatedTokenProgram.createIdempotent(
                        payer.getPublicKey(), owner, mint.getPublicKey()));
        if (initialAtomicAmount > 0) {
            transaction.addInstruction(TokenProgram.mintTo(
                    mint.getPublicKey(), ownerAta, payer.getPublicKey(), initialAtomicAmount));
        }
        transaction.addInstruction(TokenProgram.setAuthority(
                mint.getPublicKey(),
                payer.getPublicKey(),
                retainMintAuthority ? owner : null,
                TokenProgram.AuthorityType.MINT_TOKENS));
        transaction.addInstruction(TokenProgram.setAuthority(
                mint.getPublicKey(),
                payer.getPublicKey(),
                null,
                TokenProgram.AuthorityType.FREEZE_ACCOUNT));
        if (memo != null && !memo.isBlank()) {
            transaction.addInstruction(MemoProgram.writeUtf8(payer.getPublicKey(), memo));
        }
        String signature = signAndSend(transaction, List.of(payer, mint));
        recordTransaction(signature, payer.getPublicKeyBase58(), mint.getPublicKeyBase58(),
                "SOL_CONTRACT", mint.getPublicKeyBase58(), BigDecimal.ZERO, profile().getDefaultFee(), "SENT");
        return new DeploySplMintResult(signature, mint.getPublicKeyBase58(), ownerAta.toBase58());
    }

    private String sendTokenWithFeePayer(Account sourceOwner, Account feePayer, String mintAddress,
                                         String toOwnerAddress, long atomicAmount, int decimals) {
        PublicKey mint = new PublicKey(mintAddress);
        PublicKey sourceAta = new PublicKey(addressService.associatedTokenAddress(
                sourceOwner.getPublicKeyBase58(), mintAddress));
        PublicKey destinationOwner = new PublicKey(toOwnerAddress);
        PublicKey destinationAta = new PublicKey(addressService.associatedTokenAddress(
                toOwnerAddress, mintAddress));

        Transaction transaction = new Transaction();
        if (rpc.getAccountInfo(destinationAta.toBase58()).isNull()) {
            transaction.addInstruction(AssociatedTokenProgram.createIdempotent(
                    feePayer.getPublicKey(), destinationOwner, mint));
        }
        transaction.addInstruction(TokenProgram.transferChecked(
                sourceAta, destinationAta, atomicAmount, (byte) decimals, sourceOwner.getPublicKey(), mint));
        return sourceOwner.getPublicKeyBase58().equals(feePayer.getPublicKeyBase58())
                ? signAndSend(transaction, List.of(sourceOwner))
                : signAndSend(transaction, List.of(feePayer, sourceOwner));
    }

    /**
     * 执行 SOL 提现（冻结余额、签名广播、状态追踪）。
     *
     * @param tenantId  租户 ID
     * @param orderNo   提现订单号
     * @param userId    用户 ID
     * @param from      源地址记录
     * @param toAddress 目标地址
     * @param amount    提现金额（SOL）
     * @return 交易签名
     */
    public String withdrawNative(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "solana withdrawNative");
        Optional<String> previous = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        long amountLamports = toAtomicAmount(amount, 9);
        long feeLamports = profile().getDefaultFee();
        BigDecimal fee = BigDecimal.valueOf(feeLamports).movePointLeft(9);
        int inserted = repository.createTenantWithdrawalOrder(
                tenantId, orderNo, userId, CHAIN, "SOL", from.getAddress(), from.getAccountId(),
                toAddress, amount, fee);
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        BigDecimal totalDebit = amount.add(fee);
        if (!repository.freezeLedgerBalance(tenantId, CHAIN, "SOL", from.getAccountId(), totalDebit)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient available ledger balance");
            throw new IllegalStateException("insufficient SOL ledger balance");
        }
        repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Solana withdrawal is not signable: " + orderNo);
            }
            String signature = sendNative(from, toAddress, amountLamports);
            if (repository.markWithdrawalSent(tenantId, CHAIN, orderNo, from.getAddress(), signature) != 1) {
                throw new IllegalStateException("Solana withdrawal state changed before SENT: " + orderNo);
            }
            recordTransaction(signature, from.getAddress(), toAddress, "SOL", null,
                    amount, feeLamports, "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 执行 SPL Token 提现。
     *
     * @param tenantId       租户 ID
     * @param orderNo        提现订单号
     * @param userId         用户 ID
     * @param from           源地址记录
     * @param mintAddress    Token Mint 地址
     * @param toOwnerAddress 目标所有者的 Solana 地址
     * @param amount         提现金额（代币单位）
     * @return 交易签名
     */
    public String withdrawToken(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                String mintAddress, String toOwnerAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "solana withdrawToken");
        Optional<String> previous = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        int inserted = repository.createTenantWithdrawalOrder(
                tenantId, orderNo, userId, CHAIN, token.getSymbol(), from.getOwnerAddress(),
                from.getAccountId(), toOwnerAddress, amount, BigDecimal.ZERO);
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        if (!repository.freezeLedgerBalance(tenantId, CHAIN, token.getSymbol(), from.getAccountId(), amount)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null,
                    "insufficient available token ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(
                tenantId, CHAIN, orderNo, "FROZEN", from.getOwnerAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getOwnerAddress()) != 1) {
                throw new IllegalStateException("Solana token withdrawal is not signable: " + orderNo);
            }
            String signature = sendTokenAmount(from, mintAddress, toOwnerAddress, amount, token.getDecimals());
            if (repository.markWithdrawalSent(
                    tenantId, CHAIN, orderNo, from.getOwnerAddress(), signature) != 1) {
                throw new IllegalStateException("Solana token withdrawal state changed before SENT: " + orderNo);
            }
            recordTransaction(signature, from.getAddress(), toOwnerAddress, token.getSymbol(), mintAddress,
                    amount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getOwnerAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 归集原生 SOL 到热钱包。
     *
     * @param tenantId       租户 ID
     * @param collectionNo   归集编号
     * @param from           源地址记录
     * @param hotAddress     热钱包地址
     * @param amountLamports 归集金额（lamports，将转换为 long）
     * @return 交易签名
     */
    public String collectNative(UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountLamports) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "solana collectNative");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        long fee = profile().getDefaultFee();
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String signature = sendNative(from, hotAddress, amountLamports.longValueExact());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", signature, null, null);
            recordTransaction(signature, from.getAddress(), hotAddress, "SOL", null,
                    amountLamports.movePointLeft(9), fee, "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 归集 SPL Token 到热钱包。
     *
     * <p>热钱包需要作为 fee payer 支付手续费，因此需查询热钱包对应的密钥。
     *
     * @param tenantId        租户 ID
     * @param collectionNo    归集编号
     * @param from            源地址记录
     * @param mintAddress     SPL Mint 地址
     * @param hotOwnerAddress 热钱包所有者地址
     * @param amount          归集金额（代币单位）
     * @return 交易签名
     */
    public String collectToken(UUID tenantId, String collectionNo,
                               ChainAddressRecord from, String mintAddress,
                               String hotOwnerAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "solana collectToken");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            ChainAddressRecord hot = repository.findChainAddressByAddress(
                            tenantId, CHAIN, "SOL", hotOwnerAddress)
                    .or(() -> repository.findChainAddressByAddress(tenantId, CHAIN, hotOwnerAddress))
                    .orElseThrow(() -> new IllegalStateException("missing Solana hot fee payer " + hotOwnerAddress));
            Account sourceOwner = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
            Account feePayer = keyService.account(hot.getUserId(), hot.getBiz(), hot.getAddressIndex());
            String signature = sendTokenWithFeePayer(sourceOwner, feePayer, mintAddress, hotOwnerAddress,
                    toAtomicAmount(amount, token.getDecimals()), token.getDecimals());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", signature, null, null);
            recordTransaction(signature, from.getAddress(), hotOwnerAddress, token.getSymbol(), mintAddress,
                    amount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 确认提现交易（等待链上确认并清算）。
     *
     * @param tenantId    租户 ID
     * @param orderNo     提现订单号
     * @param assetSymbol 资产符号
     * @param accountId   账户 ID
     * @param debitAmount 扣款金额
     * @return true 表示确认并结算成功
     */
    public boolean confirmWithdrawal(UUID tenantId, String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String signature = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(signature, Duration.ofMinutes(2));
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, signature, assetSymbol, accountId, debitAmount)) {
            updateConfirmedTransaction(signature, status);
            return true;
        }
        return false;
    }
    /**
     * 确认归集交易。
     *
     * @param tenantId      租户 ID
     * @param collectionNo  归集编号
     * @return true 表示确认成功
     */
    public boolean confirmCollection(UUID tenantId, String collectionNo) {
        String signature = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(signature, Duration.ofMinutes(2));
        boolean updated = repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, signature) == 1;
        updateConfirmedTransaction(signature, status);
        return updated;
    }
    /**
     * 轮询等待交易确认（confirmed 或 finalized 状态）。
     *
     * @param signature 交易签名
     * @param timeout   最长等待时间
     * @return 签名状态 JSON
     * @throws IllegalStateException 如果超时或交易失败
     */
    public JsonNode requireSuccessfulConfirmation(String signature, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode status = rpc.getSignatureStatus(signature);
            if (status != null && !status.isNull()) {
                if (!status.path("err").isNull() && !status.path("err").isMissingNode()) {
                    throw new IllegalStateException("Solana transaction failed: " + status.path("err"));
                }
                String confirmation = status.path("confirmationStatus").asText();
                if ("confirmed".equals(confirmation) || "finalized".equals(confirmation)) {
                    return status;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("confirmation wait interrupted", e);
            }
        }
        throw new IllegalStateException("Solana confirmation timeout for " + signature);
    }
    private String signAndSend(Transaction transaction, List<Account> signers) {
        transaction.setRecentBlockHash(rpc.getLatestBlockhash());
        transaction.sign(signers);
        return rpc.sendTransaction(transaction.serialize());
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private ChainAddressRecord defaultHotFeePayer(UUID tenantId) {
        return repository.listDefaultHotAddressCandidates(CHAIN, "SOL").stream()
                .filter(address -> tenantId.equals(address.getTenantId()))
                .filter(address -> Boolean.TRUE.equals(address.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing enabled Solana default hot fee payer"));
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private long toAtomicAmount(BigDecimal amount, int decimals) {
        BigInteger atomic = amount.movePointRight(decimals).toBigIntegerExact();
        return atomic.longValueExact();
    }

    private void recordTransaction(String signature, String from, String to, String symbol, String mint,
                                   BigDecimal amount, long fee, String status) {
        repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                .chain(CHAIN)
                .signature(signature)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .mintAddress(mint)
                .amount(amount)
                .feeLamports(fee)
                .confirmations(0)
                .status(status)
                .build());
    }
    private void updateConfirmedTransaction(String signature, JsonNode status) {
        JsonNode transaction = rpc.getTransaction(signature);
        repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                .chain(CHAIN)
                .signature(signature)
                .fromAddress("")
                .toAddress("")
                .assetSymbol("SOL")
                .amount(BigDecimal.ZERO)
                .feeLamports(transaction.path("meta").path("fee").asLong())
                .slot(transaction.path("slot").asLong())
                .confirmations(status.path("confirmations").isNull() ? 32 : status.path("confirmations").asInt())
                .status("CONFIRMED")
                .rawPayload(transaction.toString())
                .build());
    }
    /**
     * SPL Token Mint 部署结果。
     *
     * @param signature         部署交易签名
     * @param mintAddress       新创建的 Mint 地址
     * @param ownerTokenAccount 所有者的 Token 账户（ATA）地址
     */
    public record DeploySplMintResult(String signature, String mintAddress, String ownerTokenAccount) {
    }
}
