package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public
class InMemoryTokenRegistry implements TokenRegistry {
    private final Map<String, TokenDefinition> byKey = new ConcurrentHashMap<>();
    private final Map<String, TokenDefinition> byContract = new ConcurrentHashMap<>();

    @Override
    public Optional<TokenDefinition> find(String chain, String symbol) {
        return Optional.ofNullable(byKey.get(key(chain, symbol)));
    }

    @Override
    public Optional<TokenDefinition> findByContract(String chain, String contractAddress) {
        return Optional.ofNullable(byContract.get(key(chain, contractAddress)));
    }

    @Override
    public List<TokenDefinition> list(String chain) {
        ArrayList<TokenDefinition> tokens = new ArrayList<>();
        for (TokenDefinition token : byKey.values()) {
            if (token.getChain() != null && token.getChain().equalsIgnoreCase(chain)) {
                tokens.add(token);
            }
        }
        tokens.sort(Comparator.comparing(TokenDefinition::getSymbol));
        return Collections.unmodifiableList(tokens);
    }
    public void register(TokenDefinition tokenDefinition) {
        if (tokenDefinition == null) {
            throw new IllegalArgumentException("tokenDefinition must not be null");
        }
        byKey.put(key(tokenDefinition.getChain(), tokenDefinition.getSymbol()), tokenDefinition);
        if (tokenDefinition.getContractAddress() != null) {
            byContract.put(key(tokenDefinition.getChain(), tokenDefinition.getContractAddress()), tokenDefinition);
        }
    }
    private static String key(String chain, String value) {
        return normalize(chain) + ":" + normalize(value);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
