package com.surprising.wallet.chain.evm;

import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/** Standard EVM receipt plus the fee fields returned by OP Stack and Arbitrum nodes. */
public class EvmTransactionReceipt extends TransactionReceipt {
    private String l1Fee;
    private String gasUsedForL1;
    private String operatorFeeScalar;
    private String operatorFeeConstant;

    public String getL1Fee() { return l1Fee; }
    public void setL1Fee(String l1Fee) { this.l1Fee = l1Fee; }
    public String getGasUsedForL1() { return gasUsedForL1; }
    public void setGasUsedForL1(String gasUsedForL1) { this.gasUsedForL1 = gasUsedForL1; }
    public String getOperatorFeeScalar() { return operatorFeeScalar; }
    public void setOperatorFeeScalar(String operatorFeeScalar) { this.operatorFeeScalar = operatorFeeScalar; }
    public String getOperatorFeeConstant() { return operatorFeeConstant; }
    public void setOperatorFeeConstant(String operatorFeeConstant) { this.operatorFeeConstant = operatorFeeConstant; }

    public static class RpcResponse extends Response<EvmTransactionReceipt> { }
}
