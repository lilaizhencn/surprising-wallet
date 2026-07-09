package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;

import java.util.List;
import java.util.Optional;

public interface TokenRegistry {
    Optional<TokenDefinition> find(String chain, String symbol);

    Optional<TokenDefinition> findByContract(String chain, String contractAddress);

    List<TokenDefinition> list(String chain);
}
