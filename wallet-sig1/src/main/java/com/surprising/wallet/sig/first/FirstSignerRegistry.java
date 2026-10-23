package com.surprising.wallet.sig.first;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.sig.first.service.ISignService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable native UTXO signer lookup, assembled by constructor injection. */
@Component
public class FirstSignerRegistry {
    private final Map<String, ISignService> signers;

    public FirstSignerRegistry(List<ISignService> services) {
        Map<String, ISignService> registered = new HashMap<>();
        for (ISignService service : services) {
            String chain = service.chain().toUpperCase(Locale.ROOT);
            if (registered.putIfAbsent(chain, service) != null) {
                throw new IllegalArgumentException("duplicate first signer for " + chain);
            }
        }
        signers = Map.copyOf(registered);
    }

    public ISignService find(AssetRuntimeMetadata asset) {
        if (asset == null || asset.chain() == null) return null;
        ISignService signer = signers.get(asset.chain().toUpperCase(Locale.ROOT));
        return signer != null && signer.supports(asset) ? signer : null;
    }
}
