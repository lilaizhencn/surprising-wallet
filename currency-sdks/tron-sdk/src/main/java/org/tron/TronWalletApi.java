package org.tron;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.NodeList;
import org.tron.protos.Contract;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.*;
import org.tron.wallet.constants.Constant;
import org.tron.wallet.crypto.ECKey;
import org.tron.wallet.util.Base58;
import org.tron.wallet.util.ByteArray;
import org.tron.wallet.util.Sha256Hash;
import org.tron.wallet.util.TransactionUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * @author lilaizhen
 */
@Slf4j
public class TronWalletApi {

    private static GrpcClient rpcCli;
    private static final byte addressPreFixByte = Constant.ADD_PRE_FIX_BYTE_MAINNET;

    public static void init(String fullNodeIp) {
        rpcCli = new GrpcClient(fullNodeIp);
    }

    /**
     * 获取tron地址
     * @param pub array
     * @return
     */
    public static String getAddress(byte[] pub) {
        ECKey tronKey = ECKey.fromPublicOnly(pub);
        return encode58Check(tronKey.getAddress());
    }

    /**
     * 校验地址
     * @param input base58地址
     * @return
     */
    public static boolean addressValid(String input) {
        byte[] address = null;
        boolean result = true;
        try {
            if (input.length() == Constant.ADDRESS_SIZE) {
                //hex
                address = ByteArray.fromHexString(input);
                log.error("Hex string format {}", input);
            } else if (input.length() == 34) {
                //base58check
                address = decodeFromBase58Check(input);
                log.error("Base58check format {}", input);
            } else if (input.length() == 28) {
                //base64
                address = Base64.getDecoder().decode(input);
                log.error("Base64 format {}", input);
            } else {
                result = false;
            }
            if (result) {
                result = addressValid(address);
                if (!result) {
                    log.error("Invalid address {}", input);
                }
            }
        } catch (Exception e) {
            result = false;
        }
        return result;
    }

    public static boolean addressValid(byte[] address) {
        if (ArrayUtils.isEmpty(address)) {
            log.warn("Warning: Address is empty !!");
            return false;
        }
        if (address.length != Constant.ADDRESS_SIZE / 2) {
            log.warn(
                    "Warning: Address length need " + Constant.ADDRESS_SIZE + " but " + address.length
                            + " !!");
            return false;
        }
        if (address[0] != addressPreFixByte) {
            log.warn("Warning: Address need prefix with " + addressPreFixByte + " but "
                    + address[0] + " !!");
            return false;
        }
        //Other rule;
        return true;
    }

    public static Sha256Hash getBlockHash(Protocol.Block block) {
        return Sha256Hash.of(block.getBlockHeader().getRawData().toByteArray());
    }

    private static Protocol.Transaction setReference(Protocol.Transaction transaction, Protocol.Block newestBlock) {
        long blockHeight = newestBlock.getBlockHeader().getRawData().getNumber();
        byte[] blockHash = getBlockHash(newestBlock).getBytes();
        byte[] refBlockNum = ByteArray.fromLong(blockHeight);
        Protocol.Transaction.raw rawData = transaction.getRawData().toBuilder()
                .setRefBlockHash(ByteString.copyFrom(ByteArray.subArray(blockHash, 8, 16)))
                .setRefBlockBytes(ByteString.copyFrom(ByteArray.subArray(refBlockNum, 6, 8)))
                .build();
        return transaction.toBuilder().setRawData(rawData).build();
    }

    public static Protocol.Transaction createTransaction(byte[] from, byte[] to, long amount, String blockStr) throws IOException {
        Protocol.Transaction.Builder transactionBuilder = Protocol.Transaction.newBuilder();
        //拿到最新块 TronWalletApi.getBlock(-1);
        Protocol.Block newestBlock = Block.parseFrom(ByteArray.fromHexString(blockStr));

        Protocol.Transaction.Contract.Builder contractBuilder = Protocol.Transaction.Contract.newBuilder();
        Contract.TransferContract.Builder transferContractBuilder = Contract.TransferContract
                .newBuilder();
        transferContractBuilder.setAmount(amount);
        ByteString bsTo = ByteString.copyFrom(to);
        ByteString bsOwner = ByteString.copyFrom(from);
        transferContractBuilder.setToAddress(bsTo);
        transferContractBuilder.setOwnerAddress(bsOwner);
        try {
            Any any = Any.pack(transferContractBuilder.build());
            contractBuilder.setParameter(any);
        } catch (Exception e) {
            return null;
        }
        contractBuilder.setType(Protocol.Transaction.Contract.ContractType.TransferContract);
        transactionBuilder.getRawDataBuilder().addContract(contractBuilder)
                .setTimestamp(System.currentTimeMillis())
                .setExpiration(newestBlock.getBlockHeader().getRawData().getTimestamp() + 10 * 60 * 60 * 1000);
        Protocol.Transaction transaction = transactionBuilder.build();
        return setReference(transaction, newestBlock);
    }

    public static Protocol.Transaction signTransaction2Object(byte[] transaction, byte[] privateKey)
            throws InvalidProtocolBufferException {
        ECKey ecKey = ECKey.fromPrivate(privateKey);
        Protocol.Transaction transaction1 = Protocol.Transaction.parseFrom(transaction);
        byte[] rawdata = transaction1.getRawData().toByteArray();
        byte[] hash = Sha256Hash.hash(rawdata);
        byte[] sign = ecKey.sign(hash).toByteArray();
        return transaction1.toBuilder().addSignature(ByteString.copyFrom(sign)).build();
    }

    public static String getTransactionHash(Protocol.Transaction transaction) {
        return ByteArray.toHexString(Sha256Hash.hash(transaction.getRawData().toByteArray()));
    }


    public static Account queryAccount(String address) {
        return rpcCli.queryAccount(decodeFromBase58Check(address));
    }

    public static boolean broadcastTransaction(byte[] transactionBytes)
            throws InvalidProtocolBufferException {
        Transaction transaction = Transaction.parseFrom(transactionBytes);
        return TransactionUtils.validTransaction(transaction)
                && rpcCli.broadcastTransaction(transaction);
    }

    public static Block getBlock(long blockNum) {
        return rpcCli.getBlock(blockNum);
    }

    public static String encode58Check(byte[] input) {
        byte[] hash0 = Sha256Hash.hash(input);
        byte[] hash1 = Sha256Hash.hash(hash0);
        byte[] inputCheck = new byte[input.length + 4];
        System.arraycopy(input, 0, inputCheck, 0, input.length);
        System.arraycopy(hash1, 0, inputCheck, input.length, 4);
        return Base58.encode(inputCheck);
    }

    public static byte[] decode58Check(String input) {
        byte[] decodeCheck = Base58.decode(input);
        if (decodeCheck.length <= 4) {
            return null;
        }
        byte[] decodeData = new byte[decodeCheck.length - 4];
        System.arraycopy(decodeCheck, 0, decodeData, 0, decodeData.length);
        byte[] hash0 = Sha256Hash.hash(decodeData);
        byte[] hash1 = Sha256Hash.hash(hash0);
        if (hash1[0] == decodeCheck[decodeData.length] &&
                hash1[1] == decodeCheck[decodeData.length + 1] &&
                hash1[2] == decodeCheck[decodeData.length + 2] &&
                hash1[3] == decodeCheck[decodeData.length + 3]) {
            return decodeData;
        }
        return null;
    }

    public static byte[] decodeFromBase58Check(String addressBase58) {
        if (StringUtils.isEmpty(addressBase58)) {
            log.warn("Warning: Address is empty !!");
            return null;
        }
        return decode58Check(addressBase58);
    }

    public static String getToAddress(Transaction transaction) {
        List<Transaction.Contract> contractList = transaction.getRawData().getContractList();
        if (contractList.isEmpty()) {
            return null;
        }
        Transaction.Contract contract = contractList.get(0);
        Contract.TransferContract transferContract;
        try {
            transferContract = contract.getParameter().unpack(Contract.TransferContract.class);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
        return TronWalletApi.encode58Check(transferContract.getToAddress().toByteArray());
    }

    public static String getFrom(Transaction transaction) {
        List<Transaction.Contract> contractList = transaction.getRawData().getContractList();
        if (contractList.isEmpty()) {
            return null;
        }
        Transaction.Contract contract = contractList.get(0);
        Contract.TransferContract transferContract;
        try {
            transferContract = contract.getParameter().unpack(Contract.TransferContract.class);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
        return TronWalletApi.encode58Check(transferContract.getOwnerAddress().toByteArray());
    }

    public static Long getAmount(Transaction transaction) {
        List<Transaction.Contract> contractList = transaction.getRawData().getContractList();
        if (contractList.isEmpty()) {
            return null;
        }
        Transaction.Contract contract = contractList.get(0);
        Contract.TransferContract transferContract;
        try {
            transferContract = contract.getParameter().unpack(Contract.TransferContract.class);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
        return transferContract.getAmount();
    }

    public static Optional<NodeList> listNodes() {
        return rpcCli.listNodes();
    }

    public static GrpcAPI.NumberMessage getTotalTransaction() {
        return rpcCli.getTotalTransaction();
    }

    public static GrpcAPI.NumberMessage getNextMaintenanceTime() {
        return rpcCli.getNextMaintenanceTime();
    }

    public static Optional<Transaction> getTransactionById(String txID) {
        return rpcCli.getTransactionById(txID);
    }

    public static Optional<TransactionInfo> getTransactionInfoById(String txID) {
        return rpcCli.getTransactionInfoById(txID);
    }

    public static Optional<Block> getBlockById(String blockID) {
        return rpcCli.getBlockById(blockID);
    }

    public static SmartContract getContract(byte[] address) {
        return rpcCli.getContract(address);
    }

    public static GrpcAPI.AccountNetMessage getAccountNet(byte[] address) {
        return rpcCli.getAccountNet(address);
    }

    public static GrpcAPI.AccountResourceMessage getAccountResource(byte[] address) {
        return rpcCli.getAccountResource(address);
    }

    private static Contract.UnfreezeBalanceContract createUnfreezeBalanceContract(int resourceCode, String receiverAddress, String ownerAddress) {
        Contract.UnfreezeBalanceContract.Builder builder = Contract.UnfreezeBalanceContract
                .newBuilder();
        ByteString byteAddreess = ByteString.copyFrom(Objects.requireNonNull(decode58Check(ownerAddress)));
        builder.setOwnerAddress(byteAddreess).setResourceValue(resourceCode);

        if (receiverAddress != null && !receiverAddress.equals("")) {
            final ByteString receiverAddressBytes = ByteString.copyFrom(
                    Objects.requireNonNull(decodeFromBase58Check(receiverAddress)));
            builder.setReceiverAddress(receiverAddressBytes);
        }
        return builder.build();
    }

    public static Transaction unfreezeBalance(int resourceCode, String receiverAddress, String ownerAddress) {
        Contract.UnfreezeBalanceContract contract = createUnfreezeBalanceContract(resourceCode, receiverAddress, ownerAddress);
        return rpcCli.createTransaction(contract);
    }


    public static Transaction freezeBalance(long frozenBalance, long frozenDuration, int resourceCode, String receiverAddress, String ownerAddress) {
        Contract.FreezeBalanceContract contract = createFreezeBalanceContract(frozenBalance,
                frozenDuration, resourceCode, receiverAddress, ownerAddress);
        GrpcAPI.TransactionExtention transactionExtention = rpcCli.createTransaction2(contract);
        return processTransactionExtention(transactionExtention);
    }

    private static Contract.FreezeBalanceContract createFreezeBalanceContract(long frozenBalance,
                                                                              long frozenDuration, int resourceCode, String receiverAddress, String ownerAddress) {
        Contract.FreezeBalanceContract.Builder builder = Contract.FreezeBalanceContract.newBuilder();
        ByteString byteAddress = ByteString.copyFrom(Objects.requireNonNull(decode58Check(ownerAddress)));
        builder.setOwnerAddress(byteAddress).setFrozenBalance(frozenBalance)
                .setFrozenDuration(frozenDuration).setResourceValue(resourceCode);

        if (receiverAddress != null && !receiverAddress.equals("")) {
            ByteString receiverAddressBytes = ByteString.copyFrom(
                    Objects.requireNonNull(decodeFromBase58Check(receiverAddress)));
            builder.setReceiverAddress(receiverAddressBytes);
        }
        return builder.build();
    }

    private static Transaction processTransactionExtention(GrpcAPI.TransactionExtention transactionExtention) {
        if (transactionExtention == null) {
            return null;
        }
        GrpcAPI.Return ret = transactionExtention.getResult();
        if (!ret.getResult()) {
            System.out.println("Code = " + ret.getCode());
            System.out.println("Message = " + ret.getMessage().toStringUtf8());
            return null;
        }
        Transaction transaction = transactionExtention.getTransaction();
        if (transaction == null || transaction.getRawData().getContractCount() == 0) {
            System.out.println("Transaction is empty");
            return null;
        }
        System.out.println(
                "Receive txid = " + ByteArray.toHexString(transactionExtention.getTxid().toByteArray()));
        return transaction;
    }


    public static void main(String[] args) throws InvalidProtocolBufferException {
        boolean flag = addressValid("TP8NQ9PFQACR5nMaTKn6rAcbxBep34882g");
        System.out.println(flag);
//        byte[] bytes = decode58Check("TVancJr9JLbpZ2bpSoGTkpWTWmyV3tDQ4W");
//        System.out.println(addressValid(bytes));
//         String str = "192. 168.102.77:7005";

//        TronWalletApi.init("grpc.trongrid.io:50051");
//
//        GrpcAPI.AccountNetMessage accountNetMessage = TronWalletApi.getAccountNet(decodeFromBase58Check("TVancJr9JLbpZ2bpSoGTkpWTWmyV3tDQ4W"));
//        long freeNetUsed = accountNetMessage.getFreeNetLimit();
//        System.out.println(freeNetUsed);
//
//        GrpcAPI.AccountResourceMessage resourceMessage = TronWalletApi.getAccountResource(decode58Check("TVancJr9JLbpZ2bpSoGTkpWTWmyV3tDQ4W"));
//        System.out.println(resourceMessage.getFreeNetLimit());
//        Block block = TronWalletApi.getBlock(-1);
//        String s = ByteArray.toHexString(block.toByteArray());
//        Block block1 = Block.parseFrom(ByteArray.fromHexString(s));
//        System.out.println(block1.toString());
//         Account account = queryAccount("TVancJr9JLbpZ2bpSoGTkpWTWmyV3tDQ4W");
//        System.out.println(account.toString());
//        double sqrt = Math.sqrt(2);
//        byte[] bytes = new byte[]{4, 1};
//        //1.4142135623730951
//        String s2 = Hex.toHexString(bytes);
//        System.out.println(s2);
    }
}
