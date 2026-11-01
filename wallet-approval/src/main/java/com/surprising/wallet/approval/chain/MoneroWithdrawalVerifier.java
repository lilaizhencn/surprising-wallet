package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

/** Native holders construct and inspect the actual Monero transaction set under this exact immutable payment policy. */
final class MoneroWithdrawalVerifier {
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,json);var p=payload(candidate,MoneroSigningPayload.class,json);var asset=review.assetSnapshot();
        amount(review,"XMR",p.amount(),p.customerFee(),12);
        require(asset.nativeSymbol().equals("XMR") && asset.nativeDecimals()==12 && asset.tokenIdentifier()==null
                && p.network().equals(asset.network()) && asset.networkIdentity().equals("monero:"+p.genesisHash())
                && p.recipient().equals(review.intent().recipient()) && p.sender().equals(key.publicAddress())
                && p.sender().equals(MoneroPublicAddressCodec.encode(HexFormat.of().parseHex(key.publicKeyHex()),p.network(),true)),"Monero native key, genesis, source or exact recipient differs");
        message(candidate,HexFormat.of().parseHex(p.preimageHex(json)));
        // A new native payment session can choose another input set; only the original admitted session may recover.
        return new ApprovalWithdrawalVerifiers.Verified("XMR:"+asset.networkIdentity()+":"+p.sender()+":"+candidate.execution().sessionId(),fee(review,p.maximumFeeAtomic()),false);
    }
}
