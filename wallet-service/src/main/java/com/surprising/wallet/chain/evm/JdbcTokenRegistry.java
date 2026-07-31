package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Primary
@Component
@RequiredArgsConstructor
public class JdbcTokenRegistry implements TokenRegistry {
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 获取或查询 {@code find} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public Optional<TokenDefinition> find(String chain, String symbol) {
        return repository.findToken(chain, symbol);
    }

    /**
     * 获取或查询 {@code findByContract} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public Optional<TokenDefinition> findByContract(String chain, String contractAddress) {
        return repository.findTokenByContract(chain, contractAddress);
    }

    /**
     * 获取或查询 {@code list} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public List<TokenDefinition> list(String chain) {
        return repository.listTokens(chain);
    }
}
