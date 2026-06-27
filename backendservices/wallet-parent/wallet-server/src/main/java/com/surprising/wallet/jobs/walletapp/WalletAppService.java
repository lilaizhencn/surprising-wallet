package com.surprising.wallet.jobs.walletapp;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.common.QrCodeUtil;
import com.surprising.wallet.jobs.walletapp.WalletAuthService.WalletUser;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.chain.aptos.AptosAddressService;
import com.surprising.wallet.service.chain.solana.SolanaAddressService;
import com.surprising.wallet.service.chain.sui.SuiAddressService;
import com.surprising.wallet.service.chain.ton.TonAddressService;
import com.surprising.wallet.service.chain.xrp.XrpAddressService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import org.bitcoinj.crypto.ECKey;
import org.ethereum.crypto.EthECKey;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.ton.ton4j.smartcontract.token.ft.JettonWallet;
import org.tron.TronWalletApi;

import java.math.BigDecimal;
import java.net.URL;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class WalletAppService {
    private static final int DEFAULT_BIZ = 0;
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";
    private static final int DOGE_REGTEST_RPC_TIMEOUT_MS = 120_000;
    private static final BigDecimal DOGE_REGTEST_FAUCET_AMOUNT = new BigDecimal("25");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ChainJdbcRepository repository;
    private final AssetRoutingService assetRoutingService;
    private final WalletContext walletContext;
    private final ChainRpcNodeService rpcNodeService;
    private final PubKeyConfig pubKeyConfig;
    private final SolanaAddressService solanaAddressService;
    private final TonAddressService tonAddressService;
    private final AptosAddressService aptosAddressService;
    private final SuiAddressService suiAddressService;
    private final XrpAddressService xrpAddressService;

    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    public WalletAppService(JdbcTemplate jdbcTemplate,
                            ChainJdbcRepository repository,
                            AssetRoutingService assetRoutingService,
                            WalletContext walletContext,
                            ChainRpcNodeService rpcNodeService,
                            PubKeyConfig pubKeyConfig,
                            SolanaAddressService solanaAddressService,
                            TonAddressService tonAddressService,
                            AptosAddressService aptosAddressService,
                            SuiAddressService suiAddressService,
                            XrpAddressService xrpAddressService) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.assetRoutingService = assetRoutingService;
        this.walletContext = walletContext;
        this.rpcNodeService = rpcNodeService;
        this.pubKeyConfig = pubKeyConfig;
        this.solanaAddressService = solanaAddressService;
        this.tonAddressService = tonAddressService;
        this.aptosAddressService = aptosAddressService;
        this.suiAddressService = suiAddressService;
        this.xrpAddressService = xrpAddressService;
    }

    public Map<String, Object> assetCatalog() {
        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("chains", jdbcTemplate.queryForList("""
                select chain, network, family, native_symbol, enabled, withdraw_enabled, transfer_enabled,
                       deposit_confirmations, withdraw_confirmations, explorer_url
                  from chain_profile
                 where enabled = true
                 order by chain
                """));
        payload.put("assets", listAssets());
        return payload;
    }

    public Map<String, Object> portfolio(WalletUser user, boolean hideZero) {
        List<Map<String, Object>> catalog = listAssets();
        List<Map<String, Object>> balances = userBalanceRows(user.id(), DEFAULT_BIZ);
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> balance : balances) {
            String key = value(balance, "chain") + ":" + value(balance, "symbol");
            byKey.put(key, balance);
        }

        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        for (Map<String, Object> asset : catalog) {
            String chain = value(asset, "chain");
            String symbol = value(asset, "symbol");
            String key = chain + ":" + symbol;
            Map<String, Object> balance = byKey.get(key);
            BigDecimal available = decimal(balance == null ? null : balance.get("availableBalance"));
            BigDecimal locked = decimal(balance == null ? null : balance.get("lockedBalance"));
            BigDecimal total = decimal(balance == null ? null : balance.get("totalBalance"));
            if (hideZero && total.signum() == 0) {
                continue;
            }
            Map<String, Object> symbolRow = bySymbol.computeIfAbsent(symbol, ignored -> {
                Map<String, Object> row = orderedMap();
                row.put("symbol", symbol);
                row.put("availableBalance", BigDecimal.ZERO);
                row.put("lockedBalance", BigDecimal.ZERO);
                row.put("totalBalance", BigDecimal.ZERO);
                row.put("chains", new ArrayList<Map<String, Object>>());
                return row;
            });
            symbolRow.put("availableBalance", decimal(symbolRow.get("availableBalance")).add(available));
            symbolRow.put("lockedBalance", decimal(symbolRow.get("lockedBalance")).add(locked));
            symbolRow.put("totalBalance", decimal(symbolRow.get("totalBalance")).add(total));
            Map<String, Object> chainRow = orderedMap();
            chainRow.putAll(asset);
            chainRow.put("availableBalance", available);
            chainRow.put("lockedBalance", locked);
            chainRow.put("totalBalance", total);
            chainRow.put("addresses", userAddresses(user.id(), DEFAULT_BIZ, chain, symbol, 20));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chains = (List<Map<String, Object>>) symbolRow.get("chains");
            chains.add(chainRow);
        }

        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("user", user);
        payload.put("hideZero", hideZero);
        payload.put("assets", new ArrayList<>(bySymbol.values()));
        payload.put("assetCount", bySymbol.size());
        payload.put("note", "Balances are grouped by symbol across chains. Fiat pricing is not configured.");
        return payload;
    }

    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> depositAddress(WalletUser user, DepositAddressRequest request, boolean forceNew) {
        String chain = requireChain(request.chain());
        String symbol = requireSymbol(request.symbol());
        AssetMeta asset = requireAsset(chain, symbol);
        ChainAddressRecord record = latestAddress(user.id(), DEFAULT_BIZ, chain, symbol);
        if (record == null || forceNew || staleEvmAddress(record, asset)) {
            record = createDepositAddress(user.id(), DEFAULT_BIZ, asset, forceNew);
        }
        return addressPayload(record, asset);
    }

    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> withdraw(WalletUser user, WithdrawRequest request) {
        String chain = requireChain(request.chain());
        String symbol = requireSymbol(request.symbol());
        BigDecimal amount = positiveAmount(request.amount());
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "withdrawal requires second confirmation");
        }
        AssetMeta asset = requireAsset(chain, symbol);
        validateExternalAddress(chain, request.toAddress());
        WithdrawalTarget target = withdrawalTarget(asset, request.toAddress());
        SpendAccount spend = spendAccount(user.id(), DEFAULT_BIZ, chain, symbol, amount);
        String sourceAddress = withdrawalSourceAddress(asset, spend);
        String orderNo = "WD-" + user.id() + "-" + System.currentTimeMillis() + "-" + randomSuffix();
        int created = repository.createWithdrawalOrder(orderNo, user.id(), chain, symbol,
                sourceAddress, spend.accountId(), target.broadcastAddress(), amount, BigDecimal.ZERO);
        if (created == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicate withdrawal order");
        }
        if (!repository.freezeLedgerBalance(chain, symbol, spend.accountId(), amount)) {
            repository.updateWithdrawalStatus(chain, orderNo, "FAILED", sourceAddress, null, "insufficient balance");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance");
        }
        repository.updateWithdrawalStatus(chain, orderNo, "FROZEN", sourceAddress, null, null);

        Map<String, Object> payload = orderedMap();
        payload.put("orderNo", orderNo);
        payload.put("status", "FROZEN");
        payload.put("chain", chain);
        payload.put("symbol", symbol);
        payload.put("amount", amount);
        payload.put("fee", BigDecimal.ZERO);
        payload.put("toAddress", target.broadcastAddress());
        payload.put("requestedToAddress", target.requestedAddress());
        payload.put("fromAddress", sourceAddress);
        payload.put("debitAddress", spend.address());
        payload.put("warning", withdrawWarning(asset));
        return payload;
    }

    @Transactional(rollbackFor = Throwable.class)
    public Map<String, Object> transfer(WalletUser user, TransferRequest request) {
        String chain = requireChain(request.chain());
        String symbol = requireSymbol(request.symbol());
        BigDecimal amount = positiveAmount(request.amount());
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transfer requires second confirmation");
        }
        WalletUser receiver = findUserByEmail(request.toEmail());
        if (receiver.id() == user.id()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot transfer to yourself");
        }
        AssetMeta asset = requireAsset(chain, symbol);
        ChainAddressRecord receiverAddress = createDepositAddress(receiver.id(), DEFAULT_BIZ, asset, false);
        SpendAccount sender = spendAccount(user.id(), DEFAULT_BIZ, chain, symbol, amount);
        String transferNo = "TF-" + user.id() + "-" + System.currentTimeMillis() + "-" + randomSuffix();
        int inserted = jdbcTemplate.update("""
                        insert into wallet_transfer_order(transfer_no, from_user_id, to_user_id, chain, asset_symbol,
                                                          amount, from_account_id, to_account_id, status,
                                                          created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', now(), now())
                        on conflict (transfer_no) do nothing
                        """,
                transferNo, user.id(), receiver.id(), chain, symbol, amount,
                sender.accountId(), receiverAddress.getAccountId());
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicate transfer order");
        }
        if (!repository.debitLedgerBalance(chain, symbol, sender.accountId(), amount)) {
            jdbcTemplate.update("""
                            update wallet_transfer_order
                               set status = 'FAILED', error_message = 'insufficient balance', updated_at = now()
                             where transfer_no = ?
                            """, transferNo);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance");
        }
        repository.incrementLedgerBalance(chain, symbol, receiverAddress.getAccountId(), amount);
        jdbcTemplate.update("""
                        update wallet_transfer_order
                           set status = 'COMPLETED', completed_at = now(), updated_at = now()
                         where transfer_no = ?
                        """, transferNo);

        Map<String, Object> payload = orderedMap();
        payload.put("transferNo", transferNo);
        payload.put("status", "COMPLETED");
        payload.put("chain", chain);
        payload.put("symbol", symbol);
        payload.put("amount", amount);
        payload.put("toUser", receiver);
        payload.put("toAddress", receiverAddress.getAddress());
        return payload;
    }

    public Map<String, Object> dogeRegtestFaucet(WalletUser user) {
        if (isProductionEnvironment()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "test coin faucet is disabled in production");
        }

        AssetMeta asset = requireAsset("DOGE", "DOGE");
        if (!asset.nativeAsset() || !"regtest".equalsIgnoreCase(asset.network())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "DOGE test coin faucet requires the active DOGE network to be regtest");
        }

        ChainAddressRecord record = createDepositAddress(user.id(), DEFAULT_BIZ, asset, false);
        String txHash = dogeRegtestRpc("sendtoaddress", String.class,
                record.getAddress(), DOGE_REGTEST_FAUCET_AMOUNT);
        String minerAddress = dogeRegtestRpc("getnewaddress", String.class);
        dogeRegtestRpc("generatetoaddress", Object.class, 1, minerAddress);
        Object blockHeight = dogeRegtestRpc("getblockcount", Object.class);

        Map<String, Object> payload = orderedMap();
        payload.put("status", "SENT");
        payload.put("chain", asset.chain());
        payload.put("symbol", asset.symbol());
        payload.put("network", asset.network());
        payload.put("amount", DOGE_REGTEST_FAUCET_AMOUNT);
        payload.put("txHash", txHash);
        payload.put("blockHeight", Long.parseLong(String.valueOf(blockHeight)));
        payload.put("toAddress", record.getAddress());
        payload.put("addressIndex", record.getAddressIndex());
        payload.put("warning", "DOGE regtest coins were sent. Balance updates after the scanner processes the block.");
        return payload;
    }

    public List<Map<String, Object>> orders(WalletUser user, int limit) {
        int rowLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbcTemplate.queryForList("""
                        select 'WITHDRAW' as type, order_no as ref_no, chain, asset_symbol, amount, fee,
                               status, to_address, tx_hash, error_message, created_at, updated_at
                          from withdrawal_order
                         where user_id = ?
                         order by id desc
                         limit ?
                        """, user.id(), rowLimit));
        rows.addAll(jdbcTemplate.queryForList("""
                        select case when from_user_id = ? then 'TRANSFER_OUT' else 'TRANSFER_IN' end as type,
                               transfer_no as ref_no, chain, asset_symbol, amount, 0::numeric as fee,
                               status, null::varchar as to_address, null::varchar as tx_hash,
                               error_message, created_at, updated_at
                          from wallet_transfer_order
                         where from_user_id = ? or to_user_id = ?
                         order by id desc
                         limit ?
                        """, user.id(), user.id(), user.id(), rowLimit));
        rows.sort((left, right) -> String.valueOf(right.get("updated_at"))
                .compareTo(String.valueOf(left.get("updated_at"))));
        return rows.stream().limit(rowLimit).toList();
    }

    private ChainAddressRecord createDepositAddress(long userId, int biz, AssetMeta asset, boolean forceNew) {
        ChainAddressRecord existing = latestAddress(userId, biz, asset.chain(), asset.symbol());
        if (existing != null && !forceNew && !staleEvmAddress(existing, asset)) {
            return existing;
        }
        if (asset.nativeAsset()) {
            return createNativeAddress(userId, biz, asset, existing == null ? null : existing.getAddressIndex());
        }
        if (isEvm(asset.chain()) || "TRON".equals(asset.chain()) || "XRP".equals(asset.chain())) {
            AssetMeta nativeAsset = requireAsset(asset.chain(), asset.nativeSymbol());
            ChainAddressRecord nativeAddress = forceNew
                    ? createNativeAddress(userId, biz, nativeAsset)
                    : createDepositAddress(userId, biz, nativeAsset, false);
            return mirrorTokenAddress(asset, nativeAddress);
        }
        if ("SOLANA".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return solanaAddressService.createTokenAddress(
                    asset.symbol(), asset.contractAddress(), userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("TON".equals(asset.chain())) {
            AssetMeta nativeAsset = requireAsset(asset.chain(), asset.nativeSymbol());
            ChainAddressRecord nativeAddress = forceNew
                    ? createNativeAddress(userId, biz, nativeAsset)
                    : createDepositAddress(userId, biz, nativeAsset, false);
            String jettonWalletAddress = tonJettonWalletAddress(nativeAddress.getAddress(), asset.contractAddress());
            return tonAddressService.registerJettonWallet(asset.symbol(), jettonWalletAddress,
                    userId, biz, nativeAddress.getAddressIndex(), WALLET_ROLE_DEPOSIT);
        }
        if ("APTOS".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return aptosAddressService.createCoinAddress(asset.symbol(), userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("SUI".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return suiAddressService.createCoinAddress(asset.symbol(), userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "deposit address generation is not available for " + asset.chain() + "/" + asset.symbol());
    }

    private ChainAddressRecord createNativeAddress(long userId, int biz, AssetMeta asset) {
        return createNativeAddress(userId, biz, asset, null);
    }

    private ChainAddressRecord createNativeAddress(long userId, int biz, AssetMeta asset, Long preferredIndex) {
        ChainType chainType = ChainType.valueOf(asset.chain());
        if (chainType.isUtxo()) {
            RuntimeAsset runtimeAsset = assetRoutingService.runtimeAssetByChain(asset.chain());
            IWallet wallet = walletContext.getWallet(runtimeAsset);
            if (wallet == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "wallet runtime is not available for " + asset.chain());
            }
            Address address = wallet.genNewAddress(userId, biz);
            return repository.findChainAddress(asset.chain(), asset.symbol(), userId, biz,
                    address.getIndex(), WALLET_ROLE_DEPOSIT).orElseThrow();
        }
        if (isEvm(asset.chain()) || "TRON".equals(asset.chain())) {
            long index = preferredIndex == null
                    ? nextAddressIndex(asset.chain(), asset.symbol(), userId, biz)
                    : preferredIndex;
            return deriveAccountAddress(userId, biz, index, asset);
        }
        if ("SOLANA".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return solanaAddressService.createNativeAddress(userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("TON".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return tonAddressService.createNativeAddress(userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("APTOS".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return aptosAddressService.createNativeAddress(userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("SUI".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return suiAddressService.createNativeAddress(userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        if ("XRP".equals(asset.chain())) {
            long index = nextAddressIndex(asset.chain(), asset.symbol(), userId, biz);
            return xrpAddressService.createNativeAddress(userId, biz, index, WALLET_ROLE_DEPOSIT);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "wallet runtime is not available for " + asset.chain());
    }

    private ChainAddressRecord deriveAccountAddress(long userId, int biz, long index, AssetMeta asset) {
        ChainAddressRecord record = buildAccountAddressRecord(userId, biz, index, asset);
        repository.upsertChainAddress(record);
        return repository.findChainAddress(asset.chain(), asset.symbol(), userId, biz, index, WALLET_ROLE_DEPOSIT)
                .orElseThrow();
    }

    private ChainAddressRecord buildAccountAddressRecord(long userId, int biz, long index, AssetMeta asset) {
        AccountChainProfile profile = repository.findProfileByChain(asset.chain())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "chain profile is not enabled"));
        int coinType = ChainType.derivationCoinType(asset.chain(), profile.getBip44CoinType());
        ECKey ecKey = pubKeyConfig.NODE2.getChild(44)
                .getChild(coinType)
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(index))
                .getEcKey();
        String address = "TRON".equals(asset.chain())
                ? TronWalletApi.getAddress(ecKey.getPubKey())
                : "0x" + Hex.toHexString(EthECKey.fromPublicOnly(ecKey.getPubKey()).getAddress());
        String accountId = "TRON".equals(asset.chain())
                ? address.toLowerCase(Locale.ROOT)
                : address.toLowerCase(Locale.ROOT);
        ChainAddressRecord record = ChainAddressRecord.builder()
                .chain(asset.chain())
                .assetSymbol(asset.symbol())
                .accountId(accountId)
                .userId(userId)
                .biz(biz)
                .addressIndex(index)
                .address("TRON".equals(asset.chain()) ? address : address.toLowerCase(Locale.ROOT))
                .ownerAddress(address)
                .derivationPath("m/44/" + coinType + "/" + biz + "/" + userId + "/" + index)
                .walletRole(WALLET_ROLE_DEPOSIT)
                .enabled(true)
                .build();
        return record;
    }

    private ChainAddressRecord mirrorTokenAddress(AssetMeta token, ChainAddressRecord nativeAddress) {
        ChainAddressRecord existing = repository.findChainAddress(
                token.chain(), token.symbol(), nativeAddress.getUserId(), nativeAddress.getBiz(),
                nativeAddress.getAddressIndex(), WALLET_ROLE_DEPOSIT).orElse(null);
        if (existing != null
                && sameAddress(existing.getAddress(), nativeAddress.getAddress())
                && Objects.equals(existing.getDerivationPath(), nativeAddress.getDerivationPath())) {
            return existing;
        }
        ChainAddressRecord record = ChainAddressRecord.builder()
                .chain(token.chain())
                .assetSymbol(token.symbol())
                .accountId(nativeAddress.getAccountId())
                .userId(nativeAddress.getUserId())
                .biz(nativeAddress.getBiz())
                .addressIndex(nativeAddress.getAddressIndex())
                .address(nativeAddress.getAddress())
                .ownerAddress(nativeAddress.getOwnerAddress())
                .derivationPath(nativeAddress.getDerivationPath())
                .walletRole(WALLET_ROLE_DEPOSIT)
                .enabled(true)
                .build();
        repository.upsertChainAddress(record);
        return repository.findChainAddress(
                token.chain(), token.symbol(), nativeAddress.getUserId(), nativeAddress.getBiz(),
                nativeAddress.getAddressIndex(), WALLET_ROLE_DEPOSIT).orElseThrow();
    }

    private String tonJettonWalletAddress(String ownerAddress, String jettonMasterAddress) {
        if (jettonMasterAddress == null || jettonMasterAddress.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TON token contract address is not configured");
        }
        boolean testnet = repository.findProfileByChain("TON")
                .map(profile -> profile.getNetwork().toLowerCase(Locale.ROOT).contains("test"))
                .orElse(true);
        return JettonWallet.calculateUserJettonWalletAddress(
                        0,
                        org.ton.ton4j.address.Address.of(ownerAddress),
                        org.ton.ton4j.address.Address.of(jettonMasterAddress),
                        JettonWallet.CODE_CELL)
                .toString(true, true, true, testnet);
    }

    private boolean staleEvmAddress(ChainAddressRecord record, AssetMeta asset) {
        if (record == null || !isEvm(asset.chain()) || record.getAddressIndex() == null) {
            return false;
        }
        ChainAddressRecord expected = buildAccountAddressRecord(
                record.getUserId(), record.getBiz(), record.getAddressIndex(), asset);
        return !sameAddress(record.getAddress(), expected.getAddress())
                || !Objects.equals(record.getDerivationPath(), expected.getDerivationPath());
    }

    private boolean sameAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private ChainAddressRecord latestAddress(long userId, int biz, String chain, String symbol) {
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
                chain, symbol, userId, biz, WALLET_ROLE_DEPOSIT);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long nextAddressIndex(String chain, String symbol, long userId, int biz) {
        return repository.findMaxChainAddressIndex(chain, symbol, userId, biz, WALLET_ROLE_DEPOSIT)
                .map(value -> value + 1)
                .orElse(0L);
    }

    private SpendAccount spendAccount(long userId, int biz, String chain, String symbol, BigDecimal amount) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select lb.account_id, ca.address, lb.available_balance
                          from ledger_balance lb
                          join chain_address ca
                            on ca.chain = lb.chain
                           and ca.asset_symbol = lb.asset_symbol
                           and lower(ca.account_id) = lower(lb.account_id)
                           and ca.enabled = true
                         where ca.user_id = ?
                           and ca.biz = ?
                           and lb.chain = ?
                           and lb.asset_symbol = ?
                           and lb.available_balance >= ?
                         order by lb.available_balance desc, ca.address_index desc
                         limit 1
                        """, userId, biz, chain, symbol, amount);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance");
        }
        Map<String, Object> row = rows.get(0);
        return new SpendAccount(String.valueOf(row.get("account_id")), String.valueOf(row.get("address")));
    }

    private String withdrawalSourceAddress(AssetMeta asset, SpendAccount spend) {
        if (!isAccountChain(asset.chain())) {
            return spend.address();
        }
        String hotSymbol = asset.nativeAsset() ? asset.symbol() : asset.nativeSymbol();
        if ("TON".equals(asset.chain()) && !asset.nativeAsset()) {
            hotSymbol = asset.symbol();
        }
        List<ChainAddressRecord> candidates = repository.listDefaultHotAddressCandidates(asset.chain(), hotSymbol);
        if (candidates.isEmpty() && !asset.nativeAsset()) {
            candidates = repository.listDefaultHotAddressCandidates(asset.chain(), asset.nativeSymbol());
        }
        return candidates.isEmpty() ? spend.address() : candidates.getFirst().getAddress();
    }

    private static boolean isAccountChain(String chain) {
        try {
            return !ChainType.valueOf(chain).isUtxo();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private WalletUser findUserByEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select id, email, display_name
                          from wallet_user
                         where email = ? and status = 'ACTIVE'
                        """, normalized);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver wallet user not found");
        }
        Map<String, Object> row = rows.get(0);
        return new WalletUser(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("email")),
                String.valueOf(row.get("display_name")));
    }

    private List<Map<String, Object>> listAssets() {
        return jdbcTemplate.queryForList("""
                select a.chain, a.symbol, a.asset_kind as assetKind, a.contract_address as contractAddress,
                       a.decimals, a.native_asset as nativeAsset, a.min_transfer as minTransfer,
                       a.min_withdraw as minWithdraw, cp.network, cp.family, cp.native_symbol as nativeSymbol,
                       tc.standard, tc.token_standard as tokenStandard
                  from chain_asset a
                  join chain_profile cp on cp.chain = a.chain and cp.enabled = true
                  left join token_config tc on tc.chain = a.chain and tc.symbol = a.symbol and tc.enabled = true
                 where a.active = true
                 order by a.native_asset desc, a.symbol, a.chain
                """);
    }

    private List<Map<String, Object>> userBalanceRows(long userId, int biz) {
        return jdbcTemplate.queryForList("""
                select lb.chain,
                       lb.asset_symbol as symbol,
                       coalesce(sum(lb.available_balance), 0) as "availableBalance",
                       coalesce(sum(lb.locked_balance), 0) as "lockedBalance",
                       coalesce(sum(lb.total_balance), 0) as "totalBalance"
                  from ledger_balance lb
                 where exists (
                       select 1
                         from chain_address ca
                        where ca.chain = lb.chain
                          and ca.asset_symbol = lb.asset_symbol
                          and lower(ca.account_id) = lower(lb.account_id)
                          and ca.user_id = ?
                          and ca.biz = ?
                          and ca.enabled = true
                 )
                 group by lb.chain, lb.asset_symbol
                 order by lb.asset_symbol, lb.chain
                """, userId, biz);
    }

    private List<Map<String, Object>> userAddresses(long userId, int biz, String chain, String symbol, int limit) {
        return jdbcTemplate.queryForList("""
                select chain, asset_symbol as symbol, account_id, address, owner_address as "ownerAddress",
                       address_index as "addressIndex", derivation_path as "derivationPath", created_at
                  from chain_address
                 where user_id = ? and biz = ? and chain = ? and asset_symbol = ? and enabled = true
                 order by address_index desc, id desc
                 limit ?
                """, userId, biz, chain, symbol, limit);
    }

    private AssetMeta requireAsset(String chain, String symbol) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select a.chain, a.symbol, a.asset_kind, a.contract_address, a.decimals, a.native_asset,
                       cp.network, cp.family, cp.native_symbol,
                       tc.standard, tc.token_standard,
                       coalesce(tc.contract_address, tc.contract_address_base58, tc.contract_address_hex,
                                a.contract_address) as token_contract_address
                  from chain_asset a
                  join chain_profile cp on cp.chain = a.chain and cp.enabled = true
                  left join token_config tc on tc.chain = a.chain and tc.symbol = a.symbol and tc.enabled = true
                 where a.chain = ? and a.symbol = ? and a.active = true
                 limit 1
                """, chain, symbol);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset is not enabled: " + chain + "/" + symbol);
        }
        Map<String, Object> row = rows.get(0);
        return new AssetMeta(
                value(row, "chain"),
                value(row, "symbol"),
                Boolean.TRUE.equals(row.get("native_asset")),
                value(row, "native_symbol"),
                value(row, "family"),
                value(row, "network"),
                row.get("decimals") instanceof Number number ? number.intValue() : 18,
                valueOr(row, "token_contract_address", value(row, "contract_address")),
                valueOr(row, "token_standard", value(row, "standard")));
    }

    private Map<String, Object> addressPayload(ChainAddressRecord record, AssetMeta asset) {
        Map<String, Object> payload = orderedMap();
        payload.put("chain", record.getChain());
        payload.put("symbol", record.getAssetSymbol());
        payload.put("network", asset.network());
        payload.put("standard", asset.standard());
        payload.put("address", record.getAddress());
        payload.put("qrCodeDataUrl", "data:image/png;base64," + QrCodeUtil.base64(record.getAddress()));
        payload.put("ownerAddress", record.getOwnerAddress());
        payload.put("accountId", record.getAccountId());
        payload.put("addressIndex", record.getAddressIndex());
        payload.put("derivationPath", record.getDerivationPath());
        payload.put("memo", null);
        payload.put("warnings", depositWarnings(asset));
        return payload;
    }

    private static List<String> depositWarnings(AssetMeta asset) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Only deposit " + asset.symbol() + " on " + asset.chain() + " to this address.");
        if (!asset.nativeAsset()) {
            warnings.add(asset.symbol() + " deposits on other chains require switching to that chain first.");
            warnings.add("This token transfer needs " + asset.nativeSymbol() + " as gas on " + asset.chain() + ".");
            if ("XRP".equals(asset.chain())) {
                warnings.add("XRPL issued-currency deposits require this address to be activated and to have a matching trustline.");
            }
        }
        return warnings;
    }

    private static String withdrawWarning(AssetMeta asset) {
        if (asset.nativeAsset()) {
            return "Network withdrawals are irreversible after broadcast. Check the address carefully.";
        }
        return asset.symbol() + " withdrawal uses " + asset.chain()
                + ". The receiving platform must support this token on the same chain.";
    }

    private static void validateExternalAddress(String chain, String address) {
        String value = address == null ? "" : address.trim();
        if (value.isBlank() || value.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid withdrawal address is required");
        }
        if (isEvm(chain) && !value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid EVM address");
        }
        if ("TRON".equals(chain) && !value.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid TRON address");
        }
        if ("XRP".equals(chain) && !value.matches("^r[1-9A-HJ-NP-Za-km-z]{25,34}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid XRP address");
        }
    }

    private WithdrawalTarget withdrawalTarget(AssetMeta asset, String address) {
        String value = address == null ? "" : address.trim();
        ChainAddressRecord record = repository.findChainAddressByAddress(asset.chain(), asset.symbol(), value)
                .or(() -> repository.findChainAddressByAddress(asset.chain(), value))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "withdrawal address must belong to this wallet project"));
        String broadcastAddress = value;
        if (!asset.nativeAsset() && ("SOLANA".equals(asset.chain()) || "TON".equals(asset.chain()))) {
            broadcastAddress = tokenOwnerAddress(record);
        }
        return new WithdrawalTarget(value, broadcastAddress);
    }

    private static String tokenOwnerAddress(ChainAddressRecord record) {
        String owner = record.getOwnerAddress();
        if (owner == null || owner.isBlank()) {
            owner = record.getAccountId();
        }
        if (owner == null || owner.isBlank()) {
            return record.getAddress();
        }
        return owner;
    }

    private <T> T dogeRegtestRpc(String method, Class<T> responseType, Object... params) {
        return rpcNodeService.withFailover("DOGE", "regtest", "rpc", node -> {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-type", "application/json");
                headers.putAll(rpcNodeService.authHeaders(node));
                JsonRpcHttpClient client = new JsonRpcHttpClient(new URL(node.getRpcUrl()), headers);
                client.setConnectionTimeoutMillis(DOGE_REGTEST_RPC_TIMEOUT_MS);
                client.setReadTimeoutMillis(DOGE_REGTEST_RPC_TIMEOUT_MS);
                T result = client.invoke(method, params, responseType);
                if (result == null) {
                    throw new IllegalStateException("DOGE regtest RPC returned empty result: " + method);
                }
                return result;
            } catch (Throwable e) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("DOGE regtest RPC call failed: " + method, e);
            }
        });
    }

    private boolean isProductionEnvironment() {
        String value = environmentName == null ? "" : environmentName.trim();
        return "prod".equalsIgnoreCase(value) || "production".equalsIgnoreCase(value);
    }

    private static boolean isEvm(String chain) {
        try {
            return ChainType.valueOf(chain).isEvm();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String requireChain(String chain) {
        String value = chain == null ? "" : chain.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chain is required");
        }
        try {
            ChainType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported chain: " + value);
        }
        return value;
    }

    private static String requireSymbol(String symbol) {
        String value = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset symbol is required");
        }
        return value;
    }

    private static BigDecimal positiveAmount(String amount) {
        try {
            BigDecimal value = new BigDecimal(amount == null ? "" : amount.trim());
            if (value.signum() <= 0) {
                throw new NumberFormatException();
            }
            return value.stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String value(Map<String, Object> row, String key) {
        return valueOr(row, key, "");
    }

    private static String valueOr(Map<String, Object> row, String key, String fallback) {
        Object value = row.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static String randomSuffix() {
        return Integer.toUnsignedString(RANDOM.nextInt(), 36).replace("-", "");
    }

    private static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private record AssetMeta(
            String chain,
            String symbol,
            boolean nativeAsset,
            String nativeSymbol,
            String family,
            String network,
            int decimals,
            String contractAddress,
            String standard
    ) {
    }

    private record SpendAccount(String accountId, String address) {
    }

    private record WithdrawalTarget(String requestedAddress, String broadcastAddress) {
    }

    public record DepositAddressRequest(String chain, String symbol) {
    }

    public record WithdrawRequest(String chain, String symbol, String toAddress, String amount, Boolean confirmed) {
    }

    public record TransferRequest(String chain, String symbol, String toEmail, String amount, Boolean confirmed) {
    }
}
