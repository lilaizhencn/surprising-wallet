package com.surprising.wallet.service.wallet.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.client.command.EthCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.common.utils.EthereumUtil;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.service.CurrencyBalanceService;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author lilaizhen
 * @data 17/04/2018
 */
@Slf4j
@Component
public class Erc20Wallet extends AbstractEthLikeWallet implements IWallet {
  @Autowired
  EthCommand command;
  @Autowired
  protected CurrencyBalanceService balanceService;
  protected Web3j web3j;
  @Value("${atomex.eth.withdraw.address}")
  protected String withdrawAddress;
  @Value("${atomex.eth.server}")
  private String rpcServerUrl;

  @PostConstruct
  public void init() {
    RESERVED = BigDecimal.ZERO;
    super.setCommand(command);
    super.setWithdrawAddress(withdrawAddress);
    Web3jService web3jService = new HttpService(rpcServerUrl);
    web3j = Web3j.build(web3jService);
  }

  @Override
  public BigDecimal getBalance(CurrencyEnum currency) {
    String currencyName = currency.getName();
    log.info("get {} Balance begin", currencyName);

    try {
      AccountTransactionExample example = new AccountTransactionExample();
      example.createCriteria().andStatusLessThan((byte) Constants.CONFIRM).andAddressNotEqualTo(getWithdrawAddress())
              .andConfirmNumGreaterThanOrEqualTo(currency.getDepositConfirmNum()).andBalanceGreaterThan(BigDecimal.ZERO);

      ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
      List<AccountTransaction> accountTransactions = accountTransactionService.getByExample(example, table);
      Set<String> addresses = accountTransactions.parallelStream().map(AccountTransaction::getAddress).collect(Collectors.toSet());
      addresses.add(getWithdrawAddress());

      BigDecimal total = addresses.parallelStream().map((address) -> getBalance(address, currency)).reduce(BigDecimal.ZERO, BigDecimal::add);
      log.info("get {} Balance end", currencyName);

      return total;
    } catch (Throwable e) {
      log.error("get {} Balance error", currencyName, e);

    }
    return BigDecimal.ZERO;

  }

  public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
    Web3jService web3jService = new HttpService("https://ethereum.polarisex.com");
//        Web3jService web3jService = new HttpService("https://mainnet.infura.io/v3/f18e59cbca624527aea1b3093520f0e8");
    Web3j web3j = Web3j.build(web3jService);
    //填写合约方法和查询地址 to填写合约地址
    Function function = new Function("balances", Arrays
            .asList(new Address("0x994caa65e0e2b5420a4c94f169b17d2562615a84")), Arrays.asList(TypeReference.create(Uint256.class)));
    String encodedFunction = FunctionEncoder.encode(function);
    EthCall response = web3j.ethCall(
            Transaction.createEthCallTransaction("0x994caa65e0e2b5420a4c94f169b17d2562615a84", "0xdac17f958d2ee523a2206206994597c13d831ec7", encodedFunction),
            DefaultBlockParameterName.LATEST)
            .sendAsync().get();
    List<Type> returnTypes = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
    if (!CollectionUtils.isEmpty(returnTypes)) {
      String value = response.getValue();
      BigDecimal balance = new BigDecimal(EthereumUtil.hexToBigInteger(value)).divide(BigDecimal.valueOf(100_0000_0000_0000_0000L));
      System.out.println(balance.toPlainString());

//      Event event = new Event("Transfer", Arrays.asList(new TypeReference<Address>() {
//                                                        },
//              new TypeReference<Address>() {
//              },
//              new TypeReference<Uint256>() {
//              }));
//      String encodedEventSignature = EventEncoder.encode(event);
//      EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(10406134)),
//              DefaultBlockParameter.valueOf(BigInteger.valueOf(10406134)), "0xdac17f958d2ee523a2206206994597c13d831ec7");
//      filter.addSingleTopic(encodedEventSignature);
//      EthLog ethLog = web3j.ethGetLogs(filter).send();
//      List<EthLog.LogResult> resultLogs = ethLog.getLogs();
//
//      EthLog.LogResult resultLog = resultLogs.get(0);
//      Log lg = (Log) resultLog.get();
//      System.out.println(lg.toString());
    }
  }

  @Override
  public BigDecimal getBalance(String address, CurrencyEnum currency) {
    BigDecimal balance = new BigDecimal("0");

    Function function = new Function("balanceOf", Arrays
            .asList(new Address(address)), Arrays.asList(TypeReference.create(Uint256.class)));

    String encodedFunction = FunctionEncoder.encode(function);

    try {
      EthCall response = web3j.ethCall(
              Transaction.createEthCallTransaction(address, currency.getContractAddress(), encodedFunction),
              DefaultBlockParameterName.LATEST)
              .sendAsync().get();

      List<Type> returnTypes = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
      if (!CollectionUtils.isEmpty(returnTypes)) {
        String value = response.getValue();
        balance = new BigDecimal(EthereumUtil.hexToBigInteger(value)).divide(currency.getDecimal());

      }
    } catch (Throwable e) {
      log.error("getBalance error, address:{}", address, e);
    }

    return balance;
  }

  @Override
  public void updateTotalCurrencyBalance() {
    log.info("updateTotalCurrencyBalance erc20 begin");
    CurrencyEnum.ERC20_SET.parallelStream().forEach((currency) -> {
      log.info("update {} total Balance begin", currency.getName());
      BigDecimal balance = getBalance(currency);
      updateTotalCurrencyBalance(currency, balance);
      log.info("update {} total Balance end", currency.getName());

    });

    log.info("updateTotalCurrencyBalance erc20 end");

  }


  public List<TransactionDTO> findRelatedTxs(Long height, Long bestHeight) {
    log.info("erc20 findRelatedTxs, height:{} begin", height);
    List<AccountTransaction> txs = CurrencyEnum.ERC20_SET.parallelStream().map(currency -> findErc20Txs(height, bestHeight, currency))
            .filter((transactions) -> !CollectionUtils.isEmpty(transactions)).flatMap(List::parallelStream).collect(Collectors.toList());

    List<TransactionDTO> dtos = new LinkedList<>();
    if (!org.apache.commons.collections4.CollectionUtils.isEmpty(txs)) {
      dtos = txs.parallelStream()
              .map(tx -> {
                CurrencyEnum currency = CurrencyEnum.parseValue(tx.getCurrency());
                ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
                accountTransactionService.addOnDuplicateKey(tx, table);
                return convertAccountTxToDto(tx);
              })
              .collect(Collectors.toList());
    }

    log.info("erc20 findRelatedTxs, height:{} end,size:{}", height, txs.size());
    return dtos;
  }

  protected String analyseAddress(String origin) {
    String zeros = "000000000000000000000000";
    String address = origin.replace(zeros, "");
    return address;
  }

  protected List<AccountTransaction> findErc20Txs(Long height, Long bestHeight, CurrencyEnum currency) {
    try {
      log.info("{} findRelatedTxs, height:{} begin", currency.getName(), height);
      //TODO
      Event event = new Event("Transfer", Arrays.asList(new TypeReference<Address>() {
                                                        },
              new TypeReference<Address>() {
              },
              new TypeReference<Uint256>() {
              }));
      String encodedEventSignature = EventEncoder.encode(event);
      EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(height)),
              DefaultBlockParameter.valueOf(BigInteger.valueOf(height)), currency.getContractAddress());
      filter.addSingleTopic(encodedEventSignature);
      EthLog ethLog = web3j.ethGetLogs(filter).send();
      List<EthLog.LogResult> resultLogs = ethLog.getLogs();
      List<AccountTransaction> transactions = null;
      if (!CollectionUtils.isEmpty(resultLogs)) {
        int len = resultLogs.size();
        transactions = new ArrayList<>();
        Map<String, Integer> txIdCnt = new HashMap<>();
        for (int i = 0; i < len; i++) {
          EthLog.LogResult resultLog = resultLogs.get(i);
          Log lg = (Log) resultLog.get();
          String txId = lg.getTransactionHash();
          //先检测是不是我们发出的交易
          super.updateWithdrawTXId(txId, currency);
          String to = analyseAddress(lg.getTopics().get(2));
          CurrencyEnum mainCurrency = CurrencyEnum.toMainCurrency(currency);
          com.surprising.wallet.common.pojo.Address address = searchAddress(to, mainCurrency);
          if (ObjectUtils.isEmpty(address)) {
            continue;
          }
          int decimal = currency.getDecimal().precision();
          String sciNotation = "1.0E" + (decimal - 1);
          BigInteger data = EthereumUtil.hexToBigInteger(
                  lg.getData().replace(sciNotation, ""));
          BigDecimal amount = new BigDecimal(EthereumUtil.hexToBigInteger("0x" + data.toString(16)));
          AccountTransaction transaction = AccountTransaction.builder()
                  .blockHeight(height)
                  .address(to)
                  .biz(address.getBiz())
                  .balance(amount.divide(currency.getDecimal()))
                  .createDate(Date.from(Instant.now()))
                  .txId(txId)
                  .currency(currency.getIndex())
                  .status((byte) 0)
                  .confirmNum(bestHeight - height + 1)
                  .build();

          txIdCnt.put(txId, txIdCnt.getOrDefault(txId, 0) + 1);
          if (txIdCnt.get(txId) > 1) {
            //TODO StringUtil.DASH
            transaction.setTxId(transaction.getTxId() + "" + txIdCnt.get(txId));
          }
          transactions.add(transaction);
        }
      }
      log.info("{} findRelatedTxs, height:{} end", currency.getName(), height);
      return transactions;
    } catch (Throwable e) {
      log.error("findErc20Txs error", e);
      return null;
    }
  }

  @Override
  public CurrencyEnum getCurrency() {
    return CurrencyEnum.ERC20;
  }

  /**
   * 获得当前币种的精度，用于精度转换
   *
   * @return
   */
  @Override
  public BigDecimal getDecimal() {
    throw new RuntimeException("not support this getDecimal method");
  }
}
