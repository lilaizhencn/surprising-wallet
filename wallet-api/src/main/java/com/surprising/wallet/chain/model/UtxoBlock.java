package com.surprising.wallet.chain.model;

import com.surprising.wallet.common.chain.UtxoOutput;
import java.util.List;

/** 完整读取的区块及其中匹配钱包地址的输出；不包含数据库操作。 */
public record UtxoBlock(long height, String hash, String parentHash, List<UtxoOutput> outputs) {
    public UtxoBlock { outputs = List.copyOf(outputs); }
}
