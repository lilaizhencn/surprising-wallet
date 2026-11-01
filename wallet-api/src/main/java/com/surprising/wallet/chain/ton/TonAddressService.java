package com.surprising.wallet.chain.ton;

import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;

import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Public-only owner lookup and configured-master Jetton registration; native addresses are published by MPC DKG. */
@Service
@RequiredArgsConstructor
public class TonAddressService {
    private final TonCenterClient rpc;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;

    public ChainAddressRecord requireNativeAddress(UUID tenant, long user, int biz, long index, String role) {
        Objects.requireNonNull(tenant, "TON tenant is required");
        HotWalletRules.requireAllowedReservedAddress("TON", "TON", "TON", user, biz, index, role);
        var owner = addresses.find("TON", "TON", user, biz, index, role).orElseThrow(() -> new IllegalStateException("TON owner must be published through MPC first"));
        require(tenant.equals(owner.getTenantId()) && Boolean.TRUE.equals(owner.getEnabled()) && owner.getMpcKeyId() != null,
                "TON owner is not an active tenant MPC address");
        return owner;
    }
    public ChainAddressRecord registerJettonWallet(UUID tenant, String symbol, String walletAddress,
                                                    long user, int biz, long index, String role) {
        HotWalletRules.requireAllowedReservedAddress("TON", symbol, "TON", user, biz, index, role);
        require(!"TON".equals(symbol), "TON native address is not a Jetton wallet");
        var owner = requireNativeAddress(tenant, user, biz, index, role);
        var profile = profiles.findAccountByChain("TON").orElseThrow();
        var token = tokens.findDefinition("TON", profile.getNetwork(), symbol).orElseThrow();
        String expected = rpc.jettonWallet(owner.getAddress(), token.getContractAddress());
        require(expected.equals(normalizeRaw(walletAddress)), "TON Jetton wallet differs from configured master getter");
        var existing = addresses.find("TON", symbol, user, biz, index, role);
        if (existing.isPresent()) {
            var row = existing.get();
            require(tenant.equals(row.getTenantId()) && owner.getMpcKeyId().equals(row.getMpcKeyId())
                    && owner.getAccountId().equals(row.getAccountId()) && normalizeRaw(owner.getAddress()).equals(normalizeRaw(row.getOwnerAddress()))
                    && expected.equals(normalizeRaw(row.getAddress())) && Boolean.TRUE.equals(row.getEnabled()), "TON existing Jetton registration differs from published owner");
            return row;
        }
        var record = ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(owner.getMpcKeyId())
                .chain("TON").assetSymbol(symbol).accountId(owner.getAccountId()).userId(user).biz(biz).addressIndex(index)
                .address(expected).ownerAddress(owner.getAddress()).derivationPath(owner.getDerivationPath()).walletRole(role).enabled(true).build();
        return addresses.publishMpc(record);
    }
    public String normalizeRaw(String value) { return MpcTonTransactionCodec.canonicalAddress(value); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
