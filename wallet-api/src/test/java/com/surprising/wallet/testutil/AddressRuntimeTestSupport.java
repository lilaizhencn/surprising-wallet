package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.ChainAddressRuntime;
import com.surprising.wallet.chain.ltc.LitecoinEsploraCommand;
import com.surprising.wallet.chain.rpc.*;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import static org.mockito.Mockito.mock;

/** Public validation and chain routing; custody key/address publication uses the MPC fixtures. */
public final class AddressRuntimeTestSupport {
    public final BlockchainRuntimeService runtime;
    public AddressRuntimeTestSupport(ChainRepositoryFixture repository) {
        var bitcoin = new BitcoinLikeChainRuntime(repository.chainProfileRepository, repository.chainAssetRepository,
                mock(BtcCommand.class), mock(LitecoinEsploraCommand.class), mock(DogeCommand.class), mock(BchCommand.class),
                repository.chainAddressRepository);
        runtime = new BlockchainRuntimeService(repository.chainProfileRepository, repository.chainAssetRepository,
                new ChainAddressRuntime(), bitcoin);
    }
}
