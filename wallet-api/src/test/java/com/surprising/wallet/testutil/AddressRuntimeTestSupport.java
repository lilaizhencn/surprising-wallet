package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.ChainAddressRuntime;
import com.surprising.wallet.chain.aptos.AptosKeyService;
import com.surprising.wallet.chain.cardano.CardanoKeyService;
import com.surprising.wallet.chain.ltc.LitecoinEsploraCommand;
import com.surprising.wallet.chain.monero.MoneroAddressService;
import com.surprising.wallet.chain.near.NearKeyService;
import com.surprising.wallet.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.chain.rpc.*;
import com.surprising.wallet.chain.solana.SolanaKeyService;
import com.surprising.wallet.chain.sui.SuiKeyService;
import com.surprising.wallet.chain.ton.TonKeyService;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.chain.xrp.XrpKeyService;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.service.AccountSecp256k1KeyService;
import com.surprising.wallet.service.HotWalletAddressService;

import static org.mockito.Mockito.mock;

/** Wires the production derivation and persistence path; only unused external node transports are doubles. */
public final class AddressRuntimeTestSupport {
    public final BlockchainRuntimeService runtime;
    public final HotWalletAddressService derivation;

    public AddressRuntimeTestSupport(ChainRepositoryFixture repository, WalletKeyMaterialProvider material) {
        var pub = new PubKeyConfig(material);
        derivation = new HotWalletAddressService(repository.chainAddressRepository, pub,
                new SolanaKeyService(material), new SuiKeyService(material), new AptosKeyService(material),
                new TonKeyService(material), new XrpKeyService(pub, new AccountSecp256k1KeyService(material)),
                new CardanoKeyService(material), new NearKeyService(material), new PolkadotKeyService(material),
                mock(MoneroAddressService.class));
        var accounts = new ChainAddressRuntime(repository.chainAddressRepository, repository.chainProfileRepository, derivation);
        var bitcoin = new BitcoinLikeChainRuntime(repository.chainProfileRepository, repository.chainAssetRepository, pub,
                mock(BtcCommand.class), mock(LitecoinEsploraCommand.class), mock(DogeCommand.class), mock(BchCommand.class),
                repository.chainAddressRepository);
        runtime = new BlockchainRuntimeService(repository.chainProfileRepository, repository.chainAssetRepository, accounts, bitcoin);
    }
}
