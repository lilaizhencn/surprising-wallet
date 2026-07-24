package com.surprising.wallet.custody.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.custody.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.service.chain.cardano.CardanoKeyService;
import com.surprising.wallet.service.chain.monero.MoneroAddressValidator;
import com.surprising.wallet.service.chain.near.NearKeyService;
import com.surprising.wallet.service.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Executes a withdrawal whose source account is anchored to a tenant custody address. */
@Service
public class CustodyWithdrawalExecutionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ChainJdbcRepository chains;
    private final WalletRuntimeConfigService runtimeConfig;

    public CustodyWithdrawalExecutionService(JdbcTemplate jdbc, ChainJdbcRepository chains,
                                             WalletRuntimeConfigService runtimeConfig) {
        this.jdbc = jdbc;
        this.chains = chains;
        this.runtimeConfig = runtimeConfig;
    }

    public ExecutionResult execute(UUID tenantId, AddressRecord custodyAddress,
                                   String chain, String symbol, String toAddress,
                                   BigDecimal amount, String orderPrefix) {
        AssetMeta asset = requireAsset(chain, symbol);
        validateExternalAddress(chain, toAddress);
        BigDecimal frozenAmount = amount.add(asset.networkFeeReserve());
        SpendAccount spend = requireTenantSpendAccount(
                tenantId, custodyAddress.id(), chain, symbol, frozenAmount);
        String sourceAddress = withdrawalSourceAddress(tenantId, asset, spend);
        String orderNo = normalizeOrderPrefix(orderPrefix) + "-" + System.currentTimeMillis()
                + "-" + randomSuffix();

        int created = chains.createTenantWithdrawalOrder(
                tenantId, orderNo, Integer.toUnsignedLong(custodyAddress.derivationSubject()),
                chain, symbol, sourceAddress, spend.accountId(), toAddress,
                amount, asset.networkFeeReserve());
        if (created != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicate withdrawal order");
        }
        if (!chains.freezeLedgerBalance(tenantId, chain, symbol, spend.accountId(), frozenAmount)) {
            chains.updateWithdrawalStatus(
                    tenantId, chain, orderNo, "FAILED", sourceAddress, null, "insufficient balance");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance");
        }

        boolean approvalRequired = runtimeConfig.isWithdrawalAdminApprovalRequired();
        String status = approvalRequired ? "PENDING_REVIEW" : "FROZEN";
        String statusMessage = approvalRequired
                ? "waiting for platform withdrawal approval before broadcast"
                : null;
        chains.updateWithdrawalStatus(
                tenantId, chain, orderNo, status, sourceAddress, null, statusMessage);
        return new ExecutionResult(orderNo, status, chain, symbol, amount,
                asset.networkFeeReserve(), toAddress, sourceAddress, spend.address(), approvalRequired);
    }
    private AssetMeta requireAsset(String chain, String symbol) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.native_asset, cp.native_symbol, cp.default_fee_rate,
                       cp.dust_threshold, a.decimals
                  from chain_asset a
                  join chain_profile cp
                    on cp.chain = a.chain and cp.enabled = true and cp.withdraw_enabled = true
                  left join token_config tc
                    on tc.chain = a.chain and tc.network = cp.network
                   and tc.symbol = a.symbol and tc.enabled = true
                 where a.chain = ? and a.symbol = ? and a.active = true
                   and (a.native_asset = true or tc.id is not null)
                 limit 1
                """, chain, symbol);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "asset withdrawal is not enabled: " + chain + "/" + symbol);
        }
        Map<String, Object> row = rows.getFirst();
        boolean nativeAsset = Boolean.TRUE.equals(row.get("native_asset"));
        int decimals = row.get("decimals") instanceof Number number ? number.intValue() : 18;
        BigDecimal networkFee = "XMR".equals(chain)
                ? atomicToDecimal(row.get("default_fee_rate"), decimals)
                    .max(atomicToDecimal(row.get("dust_threshold"), decimals))
                    .max(new BigDecimal("0.0001")).stripTrailingZeros()
                : BigDecimal.ZERO;
        return new AssetMeta(
                chain, symbol, nativeAsset, String.valueOf(row.get("native_symbol")), networkFee);
    }

    private SpendAccount requireTenantSpendAccount(UUID tenantId, UUID custodyAddressId,
                                                   String chain, String symbol,
                                                   BigDecimal requiredAmount) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select balance.account_id, base.address
                  from custody_address custody
                  join chain_address base
                    on base.tenant_id = custody.tenant_id
                   and base.id = custody.chain_address_id
                  join ledger_balance balance
                    on balance.tenant_id = custody.tenant_id
                   and balance.chain = base.chain
                   and balance.asset_symbol = ?
                   and lower(balance.account_id) = lower(base.account_id)
                 where custody.tenant_id = ? and custody.id = ? and custody.chain = ?
                   and custody.status = 'ACTIVE' and base.enabled = true
                   and balance.available_balance >= ?
                 order by balance.available_balance desc
                 limit 1
                """, symbol, tenantId, custodyAddressId, chain, requiredAmount);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance");
        }
        Map<String, Object> row = rows.getFirst();
        return new SpendAccount(String.valueOf(row.get("account_id")), String.valueOf(row.get("address")));
    }
    private String withdrawalSourceAddress(UUID tenantId, AssetMeta asset, SpendAccount spend) {
        if (ChainType.valueOf(asset.chain()).isUtxo()) {
            return spend.address();
        }
        return chains.findActiveTenantCollectionAddress(tenantId, asset.chain())
                .orElse(spend.address());
    }
    private static String normalizeOrderPrefix(String value) {
        String prefix = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        if (prefix.isBlank() || prefix.length() > 56) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid withdrawal order prefix");
        }
        return prefix;
    }
    private static void validateExternalAddress(String chain, String address) {
        String value = address == null ? "" : address.trim();
        if (value.isBlank() || value.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid withdrawal address is required");
        }
        if ((ChainType.valueOf(chain).isEvm() || "HYPERCORE".equals(chain))
                && !value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid EVM address");
        }
        if ("TRON".equals(chain) && !value.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid TRON address");
        }
        if ("XRP".equals(chain) && !value.matches("^r[1-9A-HJ-NP-Za-km-z]{25,34}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid XRP address");
        }
        if ("XMR".equals(chain) && !MoneroAddressValidator.isValid(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid XMR address");
        }
        if ("ADA".equals(chain) && !CardanoKeyService.isValidAddress(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ADA address");
        }
        if ("DOT".equals(chain) && !PolkadotKeyService.isValidSs58Address(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid DOT address");
        }
        if ("NEAR".equals(chain) && !NearKeyService.isValidAccountId(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid NEAR address");
        }
    }
    private static BigDecimal atomicToDecimal(Object value, int decimals) {
        return value == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(value)).movePointLeft(decimals);
    }
    private static String randomSuffix() {
        return Integer.toUnsignedString(RANDOM.nextInt(), 36).toLowerCase(Locale.ROOT);
    }

    private record AssetMeta(String chain, String symbol, boolean nativeAsset,
                             String nativeSymbol, BigDecimal networkFeeReserve) {
    }
    private record SpendAccount(String accountId, String address) {
    }

    public record ExecutionResult(String orderNo, String status, String chain, String symbol,
                                  BigDecimal amount, BigDecimal fee, String toAddress,
                                  String fromAddress, String debitAddress,
                                  boolean approvalRequired) {
    }
}
