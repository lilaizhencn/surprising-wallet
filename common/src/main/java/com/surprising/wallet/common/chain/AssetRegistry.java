package com.surprising.wallet.common.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory registry for asset metadata.
 */
public class AssetRegistry {
    private final Map<String, ChainAsset> assets = new ConcurrentHashMap<>();
    private final Map<String, TokenDefinition> tokens = new ConcurrentHashMap<>();

    public void registerAsset(ChainAsset asset) {
        if (asset == null || asset.getChain() == null || asset.getSymbol() == null) {
            throw new IllegalArgumentException("asset metadata is incomplete");
        }
        assets.put(assetKey(asset.getChain(), asset.getSymbol()), asset);
    }

    public void registerToken(TokenDefinition token) {
        if (token == null || token.getChain() == null || token.getSymbol() == null) {
            throw new IllegalArgumentException("token metadata is incomplete");
        }
        tokens.put(tokenKey(token.getChain(), token.getSymbol()), token);
    }

    public Optional<ChainAsset> findAsset(String chain, String symbol) {
        return Optional.ofNullable(assets.get(assetKey(chain, symbol)));
    }

    public Optional<TokenDefinition> findToken(String chain, String symbol) {
        return Optional.ofNullable(tokens.get(tokenKey(chain, symbol)));
    }

    public List<ChainAsset> listAssets() {
        ArrayList<ChainAsset> list = new ArrayList<>(assets.values());
        list.sort(Comparator.comparing(ChainAsset::getChain).thenComparing(ChainAsset::getSymbol));
        return Collections.unmodifiableList(list);
    }

    public List<TokenDefinition> listTokens() {
        ArrayList<TokenDefinition> list = new ArrayList<>(tokens.values());
        list.sort(Comparator.comparing(TokenDefinition::getChain).thenComparing(TokenDefinition::getSymbol));
        return Collections.unmodifiableList(list);
    }

    public Map<String, ChainAsset> snapshotAssets() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(assets));
    }

    public Map<String, TokenDefinition> snapshotTokens() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
    }

    private static String assetKey(String chain, String symbol) {
        return normalize(chain) + ":" + normalize(symbol);
    }

    private static String tokenKey(String chain, String symbol) {
        return normalize(chain) + ":" + normalize(symbol);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toUpperCase();
    }
}
