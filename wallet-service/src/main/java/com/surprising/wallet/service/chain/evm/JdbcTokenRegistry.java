package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Database-backed token registry used by production EVM/TRON scanners and
 * withdrawal builders. Token support is data driven; a token is available only
 * when an enabled row exists in token_config.
 */
@Primary
@Component
@RequiredArgsConstructor
public class JdbcTokenRegistry implements TokenRegistry {
    private final ChainJdbcRepository repository;
    @Override
    public Optional<TokenDefinition> find(String chain, String symbol) {
        return repository.findToken(chain, symbol);
    }

    @Override
    public Optional<TokenDefinition> findByContract(String chain, String contractAddress) {
        return repository.findTokenByContract(chain, contractAddress);
    }

    @Override
    public List<TokenDefinition> list(String chain) {
        return repository.listTokens(chain);
    }
}
