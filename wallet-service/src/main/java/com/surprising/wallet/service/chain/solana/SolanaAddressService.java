package com.surprising.wallet.service.chain.solana;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public
class SolanaAddressService {
    private static final String CHAIN = "SOLANA";
    private final SolanaKeyService keyService;
    private final ChainJdbcRepository repository;

    public ChainAddressRecord createNativeAddress(UUID tenantId, long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        HotWalletRules.requireAllowedReservedAddress(CHAIN, "SOL", "SOL", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, "SOL", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = new PublicKey(key.publicKey()).toBase58();
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol("SOL")
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(key.derivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, "SOL", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    public ChainAddressRecord createTokenAddress(UUID tenantId, String symbol, String mintAddress,
                                                  long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "SOL", userId, biz, derivationIndex, walletRole);
        ChainAddressRecord owner = createNativeAddress(tenantId, userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    String ata = associatedTokenAddress(owner.getAddress(), mintAddress);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAddress())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(ata)
                            .ownerAddress(owner.getAddress())
                            .derivationPath(owner.getDerivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }
    public String associatedTokenAddress(String ownerAddress, String mintAddress) {
        PublicKey owner = new PublicKey(ownerAddress);
        PublicKey mint = new PublicKey(mintAddress);
        return PublicKey.findProgramAddress(
                        List.of(owner.toByteArray(), TokenProgram.PROGRAM_ID.toByteArray(), mint.toByteArray()),
                        AssociatedTokenProgram.PROGRAM_ID)
                .getAddress()
                .toBase58();
    }
}
