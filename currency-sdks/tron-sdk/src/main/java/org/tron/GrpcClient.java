package org.tron;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.*;
import org.tron.api.GrpcAPI.Return.response_code;
import org.tron.api.WalletGrpc;
import org.tron.protos.Contract;
import org.tron.protos.Protocol.*;
import org.tron.wallet.util.ByteArray;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * @author lilaizhen
 */
@Slf4j
public class GrpcClient {

  private ManagedChannel channelFull = null;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;

  public GrpcClient(String fullNodeIp) {
    if (!StringUtils.isEmpty(fullNodeIp)) {
      this.channelFull = ManagedChannelBuilder.forTarget(fullNodeIp)
              .usePlaintext()
          .build();
      this.blockingStubFull = WalletGrpc.newBlockingStub(this.channelFull);
    }
  }

  public void shutdown() throws InterruptedException {
    if (this.channelFull != null) {
      this.channelFull.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  public TransactionExtention createTransaction2(Contract.FreezeBalanceContract contract) {
    return blockingStubFull.freezeBalance2(contract);
  }

  public Transaction createTransaction(Contract.UnfreezeBalanceContract contract) {
    return blockingStubFull.unfreezeBalance(contract);
  }

  public Account queryAccount(byte[] address) {
    ByteString addressBS = ByteString.copyFrom(address);
    Account request = Account.newBuilder().setAddress(addressBS).build();
    return this.blockingStubFull.getAccount(request);
  }

  public boolean broadcastTransaction(Transaction signaturedTransaction) {
    int i = 10;
    GrpcAPI.Return response = this.blockingStubFull.broadcastTransaction(signaturedTransaction);
    while (!response.getResult() && response.getCode() == response_code.SERVER_BUSY
        && i > 0) {
      i--;
      response = this.blockingStubFull.broadcastTransaction(signaturedTransaction);
      log.info("repeate times = " + (11 - i));
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    if (!response.getResult()) {
      log.info("Code = " + response.getCode());
      log.info("Message = " + response.getMessage().toStringUtf8());
    }
    return response.getResult();
  }

  public Block getBlock(long blockNum) {
    if (blockNum < 0) {
      return this.blockingStubFull.getNowBlock(EmptyMessage.newBuilder().build());
    }
    NumberMessage.Builder builder = NumberMessage.newBuilder();
    builder.setNum(blockNum);
    return this.blockingStubFull.getBlockByNum(builder.build());
  }

  public Optional<NodeList> listNodes() {
    NodeList nodeList = this.blockingStubFull.listNodes(EmptyMessage.newBuilder().build());
    return Optional.ofNullable(nodeList);
  }

  public AccountResourceMessage getAccountResource(byte[] address) {
    ByteString addressBS = ByteString.copyFrom(address);
    Account request = Account.newBuilder().setAddress(addressBS).build();
    return this.blockingStubFull.getAccountResource(request);
  }

  public NumberMessage getTotalTransaction() {
    return this.blockingStubFull.totalTransaction(EmptyMessage.newBuilder().build());
  }

  public NumberMessage getNextMaintenanceTime() {
    return this.blockingStubFull.getNextMaintenanceTime(EmptyMessage.newBuilder().build());
  }


  public Optional<Transaction> getTransactionById(String txID) {
    ByteString bsTxid = ByteString.copyFrom(ByteArray.fromHexString(txID));
    BytesMessage request = BytesMessage.newBuilder().setValue(bsTxid).build();
    Transaction transaction = this.blockingStubFull.getTransactionById(request);
    return Optional.ofNullable(transaction);
  }

  public Optional<TransactionInfo> getTransactionInfoById(String txID) {
    ByteString bsTxid = ByteString.copyFrom(ByteArray.fromHexString(txID));
    BytesMessage request = BytesMessage.newBuilder().setValue(bsTxid).build();
    TransactionInfo transactionInfo;
    transactionInfo = this.blockingStubFull.getTransactionInfoById(request);
    return Optional.ofNullable(transactionInfo);
  }

  public Optional<Block> getBlockById(String blockID) {
    ByteString bsTxid = ByteString.copyFrom(ByteArray.fromHexString(blockID));
    BytesMessage request = BytesMessage.newBuilder().setValue(bsTxid).build();
    Block block = this.blockingStubFull.getBlockById(request);
    return Optional.ofNullable(block);
  }

  public SmartContract getContract(byte[] address) {
    ByteString byteString = ByteString.copyFrom(address);
    BytesMessage bytesMessage = BytesMessage.newBuilder().setValue(byteString).build();
    return this.blockingStubFull.getContract(bytesMessage);
  }

  /**
   * 查询地址的网络带宽资源
   *
   * @param address
   * @return
   */
  public AccountNetMessage getAccountNet(byte[] address) {
    ByteString addressBS = ByteString.copyFrom(address);
    Account request = Account.newBuilder().setAddress(addressBS).build();
    return this.blockingStubFull.getAccountNet(request);
  }
}
