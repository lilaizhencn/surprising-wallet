package com.surprising.wallet.custody.service;

import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

@Service
public class WalletKeyConfigService {
    private final WalletKeyConfigStore store;
    private final WalletKeyMaterialProvider keyMaterial;
    private final CustodyRepository custodyRepository;

    public WalletKeyConfigService(WalletKeyConfigStore store,
                                  WalletKeyMaterialProvider keyMaterial,
                                  CustodyRepository custodyRepository) {
        this.store = store;
        this.keyMaterial = keyMaterial;
        this.custodyRepository = custodyRepository;
    }

    public KeysetView get(CustodyPrincipal actor) {
        requirePlatformAdmin(actor);
        return view(store.find());
    }

    @Transactional
    public KeysetView save(CustodyPrincipal actor, SaveKeysetCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        if (command == null) {
            throw new IllegalArgumentException("keyset body is required");
        }
        Optional<WalletKeyConfig> current = store.find();
        WalletKeyConfig requested = new WalletKeyConfig(
                command.sig1Seed(), command.sig2Seed(), command.recoverySeed(), command.ed25519Seed(),
                null, null, actor.actorId().toString());
        if (current.isPresent() && store.hasDerivedAddresses() && !sameSeeds(current.get(), requested)) {
            throw new IllegalStateException(
                    "wallet keyset cannot be changed after derived addresses have been created");
        }
        WalletKeyConfig saved = sameSeeds(current.orElse(null), requested)
                ? current.orElseThrow()
                : store.save(requested, actor.actorId().toString());
        keyMaterial.reload();
        custodyRepository.audit(null, "PLATFORM_USER", actor.actorId().toString(),
                current.isPresent() ? "WALLET_KEYSET.UPDATE" : "WALLET_KEYSET.CREATE",
                "WALLET_KEYSET", "1", sourceIp,
                "{\"seedCount\":4,\"plaintextStorage\":true}");
        return view(Optional.of(saved));
    }

    private KeysetView view(Optional<WalletKeyConfig> config) {
        if (config.isEmpty()) {
            return new KeysetView(false, false, null, null, null, null, null, null, null);
        }
        WalletKeyConfig value = config.get();
        return new KeysetView(true, store.hasDerivedAddresses(),
                value.sig1Seed(), value.sig2Seed(), value.recoverySeed(), value.ed25519Seed(),
                value.createdAt(), value.updatedAt(), value.updatedBy());
    }

    private static boolean sameSeeds(WalletKeyConfig left, WalletKeyConfig right) {
        return left != null
                && left.sig1Seed().equals(trim(right.sig1Seed()))
                && left.sig2Seed().equals(trim(right.sig2Seed()))
                && left.recoverySeed().equals(trim(right.recoverySeed()))
                && left.ed25519Seed().equals(trim(right.ed25519Seed()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    public record SaveKeysetCommand(
            String sig1Seed,
            String sig2Seed,
            String recoverySeed,
            String ed25519Seed) {
    }

    public record KeysetView(
            boolean configured,
            boolean locked,
            String sig1Seed,
            String sig2Seed,
            String recoverySeed,
            String ed25519Seed,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime updatedAt,
            String updatedBy) {
    }
}
