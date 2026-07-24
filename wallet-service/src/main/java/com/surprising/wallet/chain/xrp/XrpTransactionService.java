package com.surprising.wallet.chain.xrp;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.XrpTransactionRecord;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xrpl.xrpl4j.crypto.keys.PrivateKey;
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.TrustSet;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * XRP Ledger 交易服务，负责构建、签名和发送 XRPL 交易。
 *
 * <p>基于 xrpl4j 库实现，支持：
 * <ul>
 *   <li>原生 XRP Payment 交易</li>
 *   <li>发行货币（Issued Currency）Payment 交易</li>
 *   <li>TrustSet（建立 TrustLine）</li>
 *   <li>提现与归集流程（含冻结/签名/广播/确认全流程）</li>
 *   <li>充值地址激活（自动转入 XRP 满足准备金要求）</li>
 *   <li>发行货币充值地址自动准备（账户激活 + TrustLine 建立）</li>
 * </ul>
 *
 * <p>手续费以 drops 为单位，通过 {@link XrpRpcClient#feeDrops()} 动态获取网络费率。
 * 每笔交易设置 lastLedgerSequence = currentLedger + 20 以保证时效性。
 *
 * @see XrpRpcClient
 * @see XrpKeyService
 * @see com.surprising.wallet.chain.xrp.XrpIssuedCurrency
 */
@Service
@RequiredArgsConstructor
public
class XrpTransactionService {

    /** 链标识 */
    private static final String CHAIN = "XRP";

    /** 原生代币符号 */
    private static final String NATIVE_SYMBOL = "XRP";

    /** 激活交易专用符号（用于区分系统激活转账和普通充值） */
    public static final String ACTIVATION_SYMBOL = "XRP_ACTIVATION";

    /** XRP 小数位数 */
    private static final int XRP_DECIMALS = 6;

    /** TrustLine 限额（10 亿） */
    private static final BigDecimal TRUSTLINE_LIMIT = new BigDecimal("1000000000");

    /** 准备阶段确认最大尝试次数 */
    private static final int PREPARATION_CONFIRM_ATTEMPTS = 8;

    /** 准备阶段确认间隔 */
    private static final Duration PREPARATION_CONFIRM_SLEEP = Duration.ofSeconds(2);

    /** XRPL RPC 客户端 */
    private final XrpRpcClient rpc;

    /** 密钥服务 */
    private final XrpKeyService keyService;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** xrpl4j 签名服务 */
    private final BcSignatureService signatureService = new BcSignatureService();

    /** 运行时配置服务（可选注入，用于任务开关控制） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 发送原生 XRP 转账。
     *
     * @param from      发送方地址记录
     * @param toAddress 接收方地址
     * @param amount    转账金额（XRP）
     * @return 交易哈希
     */
    public String sendNative(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "xrp sendNative");
        return sendNativeInternal(from, toAddress, amount);
    }
    private String sendNativeInternal(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        return sendNativeInternal(from, toAddress, amount, NATIVE_SYMBOL);
    }

    private String sendNativeInternal(ChainAddressRecord from, String toAddress, BigDecimal amount,
                                      String recordSymbol) {
        validateAddress(toAddress);
        ensureActivated(from.getAddress());
        Payment payment = payment(from, toAddress, XrpCurrencyAmount.of(UnsignedLong.valueOf(toDrops(amount))));
        String txHash = signAndSubmit(from, payment);
        recordTransaction(txHash, from.getAddress(), toAddress, recordSymbol,
                null, null, amount, payment.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    /**
     * 发送发行货币（Issued Currency）转账。
     *
     * <p>需要源和目的账户都已激活且有 TrustLine。
     *
     * @param from      发送方地址记录
     * @param token     代币定义（含 issuer 和 currency code）
     * @param toAddress 接收方地址
     * @param amount    转账金额（代币单位）
     * @return 交易哈希
     */
    public String sendIssuedCurrency(ChainAddressRecord from, TokenDefinition token,
                                     String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "xrp sendIssuedCurrency");
        return sendIssuedCurrencyInternal(from, token, toAddress, amount);
    }

    private String sendIssuedCurrencyInternal(ChainAddressRecord from, TokenDefinition token,
                                             String toAddress, BigDecimal amount) {
        validateAddress(toAddress);
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        ensureActivated(from.getAddress());
        ensureActivated(toAddress);
        ensureTrustLine(from.getAddress(), issued, "source");
        if (!toAddress.equals(issued.issuer())) {
            ensureTrustLine(toAddress, issued, "destination");
        }
        Payment payment = payment(from, toAddress, issued.amount(amount));
        String txHash = signAndSubmit(from, payment);
        recordTransaction(txHash, from.getAddress(), toAddress, token.getSymbol(),
                issued.issuer(), issued.currencyCode(), amount, payment.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    /**
     * 确认提现交易（等待链上确认并结算）。
     *
     * @param tenantId    租户 ID
     * @param profile     链配置
     * @param orderNo     提现订单号
     * @param assetSymbol 资产符号
     * @param accountId   账户 ID
     * @param debitAmount 扣款金额
     * @return true 表示确认并结算成功
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, txHash, assetSymbol, accountId, debitAmount)) {
            updateConfirmedTransaction(txHash, confirmation);
            return true;
        }
        return false;
    }

    /**
     * 归集原生 XRP 到热钱包。
     *
     * @param tenantId      租户 ID
     * @param collectionNo  归集编号
     * @param from          源地址记录
     * @param hotAddress    热钱包地址
     * @param amount        归集金额（XRP）
     * @return 交易哈希
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectNative");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendNativeInternal(from, hotAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    /**
     * 计算账户可归集的 XRP 数量。
     *
     * <p>可归集 = 余额 - 准备金（base + ownerCount * inc） - 预留手续费。
     * 结果不超过候选金额 candidateAmount。
     *
     * @param address         账户地址
     * @param candidateAmount 候选归集金额
     * @return 可归集金额（XRP）
     */
    public BigDecimal collectableNativeAmount(String address, BigDecimal candidateAmount) {
        BigDecimal candidate = candidateAmount == null ? BigDecimal.ZERO : candidateAmount;
        if (candidate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Optional<XrpRpcClient.AccountState> account = rpc.accountInfo(address);
        if (account.isEmpty()) {
            return BigDecimal.ZERO;
        }
        XrpRpcClient.ReserveInfo reserve = rpc.reserveInfo();
        int ownerSlots = account.get().ownerCount() + missingIssuedCurrencyTrustLines(address);
        BigDecimal balance = fromDrops(account.get().balanceDrops());
        BigDecimal requiredReserve = reserve.baseXrp()
                .add(reserve.ownerXrp().multiply(BigDecimal.valueOf(ownerSlots)));
        BigDecimal feeReserve = fromDrops(BigDecimal.valueOf(feeDrops()));
        BigDecimal spendable = balance.subtract(requiredReserve).subtract(feeReserve);
        if (spendable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return candidate.min(spendable).stripTrailingZeros();
    }
    private int missingIssuedCurrencyTrustLines(String address) {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            tokens.put(token.getSymbol().toUpperCase(Locale.ROOT), token);
        }
        Set<String> checkedSymbols = new HashSet<>();
        int missing = 0;
        for (ChainAddressRecord record : repository.listChainAddresses(CHAIN)) {
            if (NATIVE_SYMBOL.equalsIgnoreCase(record.getAssetSymbol())
                    || !address.equals(record.getAddress())) {
                continue;
            }
            String symbol = record.getAssetSymbol().toUpperCase(Locale.ROOT);
            if (!checkedSymbols.add(symbol)) {
                continue;
            }
            TokenDefinition token = tokens.get(symbol);
            if (token == null) {
                continue;
            }
            try {
                XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
                if (!hasTrustLine(address, issued)) {
                    missing++;
                }
            } catch (IllegalArgumentException ignored) {
                // Bad token rows should not stop XRP native collection.
            }
        }
        return missing;
    }

    /**
     * 归集发行货币到热钱包。
     *
     * <p>会自动确保热钱包有对应 TrustLine，没有则自动创建。
     *
     * @param tenantId      租户 ID
     * @param collectionNo  归集编号
     * @param from          源地址记录
     * @param token         代币定义
     * @param hotAddress    热钱包地址
     * @param amount        归集金额
     * @return 交易哈希
     */
    public String collectIssuedCurrency(java.util.UUID tenantId, String collectionNo,
                                        ChainAddressRecord from,
                                        TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectIssuedCurrency");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        ensureCollectionDestinationTrustLine(token, hotAddress);
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendIssuedCurrencyInternal(from, token, hotAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    private void ensureCollectionDestinationTrustLine(TokenDefinition token, String hotAddress) {
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        if (hotAddress.equals(issued.issuer()) || hasTrustLine(hotAddress, issued)) {
            return;
        }
        ChainAddressRecord hot = repository.findChainAddressByAddress(CHAIN, NATIVE_SYMBOL, hotAddress)
                .or(() -> repository.findChainAddressByAddress(CHAIN, hotAddress))
                .orElseThrow(() -> new IllegalStateException(
                        "missing XRP hot wallet address for issued-currency collection: " + hotAddress));
        String trustSetTxHash = createTrustLine(hot, issued);
        Confirmation confirmation = waitForValidated(trustSetTxHash, "XRPL hot wallet trustline creation");
        updateSystemTransaction(trustSetTxHash, token.getSymbol(), issued.issuer(),
                issued.currencyCode(), confirmation);
    }

    /**
     * 确认归集交易。
     *
     * @param tenantId      租户 ID
     * @param profile       链配置
     * @param collectionNo  归集编号
     * @return true 表示确认成功
     */
    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        boolean updated = repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        updateConfirmedTransaction(txHash, confirmation);
        return updated;
    }
    /**
     * 确保发行货币充值地址准备就绪（激活账户 + 建立 TrustLine）。
     *
     * <p>如果账户未激活，从热钱包转入足额 XRP 来激活；
     * 如果没有 TrustLine，自动创建 TrustSet 交易。
     *
     * @param depositAddress 充值地址记录
     * @param symbol         代币符号
     * @return 准备结果（含激活和 TrustLine 状态）
     */
    public DepositPreparation ensureIssuedCurrencyDepositReady(ChainAddressRecord depositAddress, String symbol) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_TRANSFER, "xrp prepareIssuedCurrencyDepositAddress");
        TokenDefinition token = token(symbol);
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        validateAddress(depositAddress.getAddress());

        Optional<XrpRpcClient.AccountState> accountBefore = rpc.accountInfo(depositAddress.getAddress());
        boolean activatedBefore = accountBefore.isPresent();
        boolean trustLineBefore = activatedBefore && hasTrustLine(depositAddress.getAddress(), issued);
        String activationTxHash = null;
        String trustSetTxHash = null;
        BigDecimal activationAmount = BigDecimal.ZERO;

        if (!trustLineBefore) {
            BigDecimal topUp = requiredTrustLineTopUp(accountBefore, trustLineBefore);
            if (topUp.signum() > 0) {
                ChainAddressRecord hotAddress = defaultHotAddress();
                BigDecimal spendable = collectableNativeAmount(hotAddress.getAddress(), topUp);
                if (spendable.compareTo(topUp) < 0) {
                    throw new IllegalStateException("XRPL hot wallet does not have enough spendable XRP for address activation");
                }
                activationAmount = topUp;
                activationTxHash = sendNativeInternal(hotAddress, depositAddress.getAddress(), topUp, ACTIVATION_SYMBOL);
                Confirmation confirmation = waitForValidated(activationTxHash, "XRPL address activation");
                updateSystemTransaction(activationTxHash, ACTIVATION_SYMBOL, null, null, confirmation);
            }
            if (!hasTrustLine(depositAddress.getAddress(), issued)) {
                trustSetTxHash = createTrustLine(depositAddress, issued);
                Confirmation confirmation = waitForValidated(trustSetTxHash, "XRPL trustline creation");
                updateSystemTransaction(trustSetTxHash, token.getSymbol(), issued.issuer(),
                        issued.currencyCode(), confirmation);
            }
        }

        boolean activated = rpc.accountInfo(depositAddress.getAddress()).isPresent();
        boolean trustLineReady = activated && hasTrustLine(depositAddress.getAddress(), issued);
        return new DepositPreparation(activated, trustLineReady, activatedBefore, trustLineBefore,
                activationAmount, activationTxHash, trustSetTxHash);
    }
    /**
     * 检查账户是否已建立指定发行货币的 TrustLine。
     *
     * @param address 账户地址
     * @param symbol  代币符号
     * @return true 表示已有 TrustLine
     */
    public boolean hasIssuedCurrencyTrustLine(String address, String symbol) {
        return hasTrustLine(address, XrpIssuedCurrency.fromToken(token(symbol)));
    }
    private Payment payment(ChainAddressRecord from, String toAddress, CurrencyAmount amount) {
        long feeDrops = feeDrops();
        long sequence = rpc.accountSequence(from.getAddress());
        long lastLedger = rpc.latestLedgerIndex() + 20L;
        return Payment.builder()
                .account(Address.of(from.getAddress()))
                .destination(Address.of(toAddress))
                .amount(amount)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops)))
                .sequence(UnsignedInteger.valueOf(sequence))
                .lastLedgerSequence(UnsignedInteger.valueOf(lastLedger))
                .build();
    }
    private String signAndSubmit(ChainAddressRecord from, Payment payment) {
        AccountChainProfile profile = profile();
        PrivateKey privateKey = keyService.privateKey(profile, from);
        try {
            Payment signable = Payment.builder()
                    .from(payment)
                    .signingPublicKey(signatureService.derivePublicKey(privateKey))
                    .build();
            SingleSignedTransaction<Payment> signed = signatureService.sign(privateKey, signable);
            return rpc.submit(signed.signedTransactionBytes().hexValue());
        } finally {
            privateKey.destroy();
        }
    }
    private String signAndSubmit(ChainAddressRecord from, TrustSet trustSet) {
        AccountChainProfile profile = profile();
        PrivateKey privateKey = keyService.privateKey(profile, from);
        try {
            TrustSet signable = TrustSet.builder()
                    .from(trustSet)
                    .signingPublicKey(signatureService.derivePublicKey(privateKey))
                    .build();
            SingleSignedTransaction<TrustSet> signed = signatureService.sign(privateKey, signable);
            return rpc.submit(signed.signedTransactionBytes().hexValue());
        } finally {
            privateKey.destroy();
        }
    }
    private String createTrustLine(ChainAddressRecord address, XrpIssuedCurrency issued) {
        ensureActivated(address.getAddress());
        long feeDrops = feeDrops();
        long sequence = rpc.accountSequence(address.getAddress());
        long lastLedger = rpc.latestLedgerIndex() + 20L;
        IssuedCurrencyAmount limit = IssuedCurrencyAmount.builder()
                .issuer(Address.of(issued.issuer()))
                .currency(issued.currencyCode())
                .value(TRUSTLINE_LIMIT.stripTrailingZeros().toPlainString())
                .build();
        TrustSet trustSet = TrustSet.builder()
                .account(Address.of(address.getAddress()))
                .limitAmount(limit)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops)))
                .sequence(UnsignedInteger.valueOf(sequence))
                .lastLedgerSequence(UnsignedInteger.valueOf(lastLedger))
                .build();
        String txHash = signAndSubmit(address, trustSet);
        recordTransaction(txHash, address.getAddress(), issued.issuer(), issued.symbol(),
                issued.issuer(), issued.currencyCode(), BigDecimal.ZERO,
                trustSet.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    private BigDecimal requiredTrustLineTopUp(Optional<XrpRpcClient.AccountState> currentAccount,
                                              boolean trustLineExists) {
        if (trustLineExists) {
            return BigDecimal.ZERO;
        }
        XrpRpcClient.ReserveInfo reserve = rpc.reserveInfo();
        BigDecimal balance = currentAccount
                .map(account -> fromDrops(account.balanceDrops()))
                .orElse(BigDecimal.ZERO);
        int ownerCount = currentAccount.map(XrpRpcClient.AccountState::ownerCount).orElse(0);
        BigDecimal requiredReserve = reserve.baseXrp()
                .add(reserve.ownerXrp().multiply(BigDecimal.valueOf(ownerCount + 1L)));
        BigDecimal feeCushion = fromDrops(BigDecimal.valueOf(feeDrops())).multiply(BigDecimal.valueOf(3L));
        return requiredReserve.add(feeCushion)
                .subtract(balance)
                .setScale(XRP_DECIMALS, RoundingMode.CEILING)
                .max(BigDecimal.ZERO)
                .stripTrailingZeros();
    }
    private ChainAddressRecord defaultHotAddress() {
        return repository.listDefaultHotAddressCandidates(CHAIN, NATIVE_SYMBOL).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing XRP default hot wallet address"));
    }
    private TokenDefinition token(String symbol) {
        String value = symbol == null ? "" : symbol.trim();
        return repository.listTokens(CHAIN).stream()
                .filter(candidate -> candidate.getSymbol().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("XRPL issued currency not configured: " + symbol));
    }
    private Confirmation waitForValidated(String txHash, String operation) {
        AccountChainProfile profile = profile();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < PREPARATION_CONFIRM_ATTEMPTS; attempt++) {
            try {
                Confirmation confirmation = confirmation(profile, txHash);
                if (confirmation.confirmed()) {
                    return confirmation;
                }
            } catch (RuntimeException e) {
                lastFailure = e;
            }
            sleepBeforeConfirmationRetry();
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException(operation + " was submitted but not confirmed in time: " + txHash);
    }
    private void sleepBeforeConfirmationRetry() {
        try {
            Thread.sleep(PREPARATION_CONFIRM_SLEEP.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for XRPL confirmation", e);
        }
    }

    private void updateSystemTransaction(String txHash, String symbol, String issuer,
                                         String currencyCode, Confirmation confirmation) {
        JsonNode tx = txNode(confirmation.raw());
        repository.recordXrpTransaction(XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(tx.path("Account").asText(""))
                .toAddress(tx.path("Destination").asText(issuer == null ? "" : issuer))
                .assetSymbol(symbol)
                .issuerAddress(issuer)
                .currencyCode(currencyCode)
                .amount(symbol.equals(ACTIVATION_SYMBOL)
                        ? fromDrops(new BigDecimal(tx.path("Amount").asText("0")))
                        : BigDecimal.ZERO)
                .feeDrops(tx.path("Fee").asLong(0))
                .ledgerIndex(confirmation.ledgerIndex())
                .sequenceNumber(tx.path("Sequence").asLong(0))
                .confirmations((int) Math.min(Integer.MAX_VALUE, confirmation.confirmations()))
                .status("CONFIRMED")
                .rawPayload(confirmation.raw().toString())
                .build());
    }
    private Confirmation confirmation(AccountChainProfile profile, String txHash) {
        JsonNode result = rpc.transaction(txHash);
        if (!result.path("validated").asBoolean(false)) {
            return new Confirmation(false, 0, 0, result);
        }
        JsonNode tx = txNode(result);
        JsonNode meta = metaNode(result);
        if (!"tesSUCCESS".equals(meta.path("TransactionResult").asText())) {
            throw new IllegalStateException("XRPL transaction failed: "
                    + meta.path("TransactionResult").asText());
        }
        long ledger = result.path("ledger_index").asLong(tx.path("ledger_index").asLong(0));
        long confirmations = Math.max(1L, rpc.latestLedgerIndex() - ledger + 1L);
        return new Confirmation(confirmations >= Math.max(1, profile.getWithdrawConfirmations()),
                ledger, confirmations, result);
    }
    private void updateConfirmedTransaction(String txHash, Confirmation confirmation) {
        JsonNode tx = txNode(confirmation.raw());
        JsonNode amountNode = deliveredAmount(confirmation.raw());
        String from = tx.path("Account").asText("");
        String to = tx.path("Destination").asText("");
        XrpTransactionRecord.XrpTransactionRecordBuilder builder = XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .ledgerIndex(confirmation.ledgerIndex())
                .sequenceNumber(tx.path("Sequence").asLong(0))
                .confirmations((int) Math.min(Integer.MAX_VALUE, confirmation.confirmations()))
                .feeDrops(tx.path("Fee").asLong(0))
                .status("CONFIRMED")
                .rawPayload(confirmation.raw().toString());
        if (amountNode.isTextual()) {
            builder.assetSymbol(NATIVE_SYMBOL)
                    .amount(fromDrops(new BigDecimal(amountNode.asText())));
        } else {
            String currency = amountNode.path("currency").asText("");
            String issuer = amountNode.path("issuer").asText("");
            TokenDefinition token = repository.listTokens(CHAIN).stream()
                    .filter(candidate -> matchesIssuedCurrency(candidate, issuer, currency))
                    .findFirst()
                    .orElse(null);
            builder.assetSymbol(token == null ? currency : token.getSymbol())
                    .issuerAddress(issuer)
                    .currencyCode(currency)
                    .amount(new BigDecimal(amountNode.path("value").asText("0")));
        }
        repository.recordXrpTransaction(builder.build());
    }
    private boolean matchesIssuedCurrency(TokenDefinition token, String issuer, String currency) {
        try {
            return XrpIssuedCurrency.fromToken(token).matches(issuer, currency);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void recordTransaction(String txHash, String from, String to, String symbol,
                                   String issuer, String currencyCode, BigDecimal amount,
                                   long feeDrops, String status, String rawPayload) {
        repository.recordXrpTransaction(XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .issuerAddress(issuer)
                .currencyCode(currencyCode)
                .amount(amount)
                .feeDrops(feeDrops)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }
    private void ensureTrustLine(String address, XrpIssuedCurrency issued, String side) {
        if (!hasTrustLine(address, issued)) {
            throw new IllegalStateException("XRPL " + side + " account has no trustline for "
                    + issued.symbol() + " issuer=" + issued.issuer());
        }
    }
    private boolean hasTrustLine(String address, XrpIssuedCurrency issued) {
        return rpc.accountLines(address, issued.issuer()).findValues("currency").stream()
                .anyMatch(node -> issued.currencyCode().equalsIgnoreCase(node.asText()));
    }
    private void ensureActivated(String address) {
        if (rpc.accountInfo(address).isEmpty()) {
            throw new IllegalStateException("XRPL account is not activated: " + address);
        }
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private long feeDrops() {
        Long configured = profile().getDefaultFee();
        return configured == null || configured <= 0 ? rpc.feeDrops() : Math.max(10L, configured);
    }
    private BigDecimal feeAsXrp() {
        return fromDrops(BigDecimal.valueOf(feeDrops()));
    }
    private long toDrops(BigDecimal amount) {
        return amount.movePointRight(XRP_DECIMALS)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }
    private BigDecimal fromDrops(BigDecimal drops) {
        return drops.movePointLeft(XRP_DECIMALS).stripTrailingZeros();
    }
    private JsonNode txNode(JsonNode result) {
        JsonNode txJson = result.path("tx_json");
        return txJson.isMissingNode() || txJson.isNull() ? result : txJson;
    }
    private JsonNode metaNode(JsonNode result) {
        JsonNode meta = result.path("meta");
        return meta.isMissingNode() || meta.isNull() ? result.path("metaData") : meta;
    }
    private JsonNode deliveredAmount(JsonNode result) {
        JsonNode meta = metaNode(result);
        JsonNode delivered = meta.path("delivered_amount");
        if (!delivered.isMissingNode() && !delivered.isNull() && !"unavailable".equals(delivered.asText())) {
            return delivered;
        }
        return txNode(result).path("Amount");
    }
    private void validateAddress(String address) {
        Address.of(address).validateAddress();
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private record Confirmation(boolean confirmed, long ledgerIndex, long confirmations, JsonNode raw) {
    }

    /**
     * 充值地址准备结果。
     *
     * @param activated          当前是否已激活
     * @param trustLineReady     当前是否已有 TrustLine
     * @param activatedBefore    准备前是否已激活
     * @param trustLineBefore    准备前是否已有 TrustLine
     * @param activationAmount   激活转入的 XRP 金额
     * @param activationTxHash   激活交易哈希（如果执行了激活）
     * @param trustSetTxHash     TrustSet 交易哈希（如果执行了 TrustSet）
     */
    public record DepositPreparation(
            boolean activated,
            boolean trustLineReady,
            boolean activatedBefore,
            boolean trustLineBefore,
            BigDecimal activationAmount,
            String activationTxHash,
            String trustSetTxHash
    ) {
    }
}
