package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 代币注册表接口，提供按链查询代币定义的能力。
 *
 * <p>支持按代币符号和合约地址两种方式查找，以及列出某条链上的所有代币。
 * 典型实现为 {@link InMemoryTokenRegistry}。
 */
public interface TokenRegistry {

    /**
     * 按链和代币符号查找代币定义。
     *
     * @param chain  链标识
     * @param symbol 代币符号
     * @return 代币定义，如果不存在则返回 empty
     */
    Optional<TokenDefinition> find(String chain, String symbol);

    /**
     * 按链和合约地址查找代币定义。
     *
     * @param chain           链标识
     * @param contractAddress 合约地址
     * @return 代币定义，如果不存在则返回 empty
     */
    Optional<TokenDefinition> findByContract(String chain, String contractAddress);

    /**
     * 列出指定链上的所有代币定义。
     *
     * @param chain 链标识
     * @return 代币定义列表
     */
    List<TokenDefinition> list(String chain);
}
