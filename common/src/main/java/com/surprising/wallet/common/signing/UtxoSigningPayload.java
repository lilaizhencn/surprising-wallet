package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import java.util.List;
import java.util.UUID;

/** Complete immutable batch, public input keys and exact unsigned transaction, shared by every input slot. */
public record UtxoSigningPayload(UtxoSigningTask task, MpcUtxoTransactionCodec.Unsigned unsigned, List<UUID> keyIds,UUID changeKeyId) {
    public UtxoSigningPayload { java.util.Objects.requireNonNull(task);java.util.Objects.requireNonNull(unsigned);keyIds = List.copyOf(keyIds);java.util.Objects.requireNonNull(changeKeyId); }
    public static UtxoSigningPayload decode(MpcSignCommand command,tools.jackson.databind.ObjectMapper json) {
        require(command.chain().isBitcoinLike() && command.payloadEncoding().equals("UTXO_MPC_JSON_V1") && command.purpose()==MpcSignCommand.Purpose.TRANSACTION
                && command.signingSlot().matches("input/(?:0|[1-9][0-9]{0,2})"),"invalid fixed UTXO signing family or input slot");
        var payload=json.readerFor(UtxoSigningPayload.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<UtxoSigningPayload>readValue(java.util.HexFormat.of().parseHex(command.payloadHex()));
        require(payload!=null,"fixed UTXO signing payload required");
        var request=payload.task().request();int index=Integer.parseInt(command.signingSlot().substring(6));
        require(request.tenantId().equals(command.tenantId()) && request.requestId().toString().equals(command.businessReference())
                && request.operation().name().equals(command.businessType().name()) && request.asset().chain().equals(command.chain().name()) && request.asset().decimals()==8
                && payload.unsigned().chain().equals(command.chain().name()) && payload.unsigned().network()==request.asset().network()
                && !request.inputs().isEmpty() && request.inputs().size()<=1000 && !request.payments().isEmpty() && request.payments().size()<=100
                && request.inputs().size()==payload.unsigned().inputs().size() && request.inputs().size()==payload.keyIds().size()
                && index<request.inputs().size() && payload.keyIds().get(index).equals(command.keyId()),"fixed UTXO signing scope differs from its input slot");
        require(java.util.HexFormat.of().formatHex(MpcUtxoTransactionCodec.digest(payload.unsigned(),index)).equals(command.preimageHex()),"fixed UTXO signing digest differs from actual unsigned bytes");
        return payload;
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
