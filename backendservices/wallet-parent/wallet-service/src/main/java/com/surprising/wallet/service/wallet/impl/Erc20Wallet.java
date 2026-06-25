package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.EthCommand;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.utils.EthereumUtil;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author lilaizhen
 * @data 17/04/2018
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sw.legacy.account-wallet.enabled", havingValue = "true")
public class Erc20Wallet extends AbstractEthLikeWallet implements IWallet {
  @Autowired
  EthCommand command;
  @Autowired
  ChainRpcNodeService rpcNodeService;
  protected Web3j web3j;
  private RuntimeAsset mainCurrency;
  private RuntimeAsset currency;
  private List<RuntimeAsset> trackedTokens = List.of();

  @PostConstruct
  public void init() {
    super.setCommand(command);
    super.setWithdrawAddress("");
    mainCurrency = loadRuntimeAssetByChain("ETH");
    trackedTokens = assetRoutingService.runtimeTokenAssets("ETH");
    currency = trackedTokens.stream()
            .filter(token -> token.sameAsset(RuntimeAsset.USDT))
            .findFirst()
            .orElse(trackedTokens.isEmpty() ? null : trackedTokens.get(0));
    String rpcServerUrl = rpcNodeService.primaryRpcUrl("ETH", chainJdbcRepository.findProfileByChain("ETH")
            .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for ETH"))
            .getNetwork());
    Web3jService web3jService = new HttpService(rpcServerUrl);
    web3j = Web3j.build(web3jService);
  }

  @Override
  public BigDecimal getBalance(RuntimeAsset currency) {
    String currencyName = currency.getName();
    log.info("get {} Balance begin", currencyName);

    try {
      RuntimeAsset addressAsset = RuntimeAsset.toMainCurrency(currency);
      Set<String> addresses = chainJdbcRepository
              .listChainAddresses(currency.chain(), addressAsset.assetSymbol())
              .stream()
              .map(com.surprising.wallet.common.chain.ChainAddressRecord::getAddress)
              .collect(Collectors.toSet());
      if (StringUtils.hasText(getWithdrawAddress())) {
        addresses.add(getWithdrawAddress());
      }

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
  public BigDecimal getBalance(String address, RuntimeAsset currency) {
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
  public void transfer(String address, RuntimeAsset currency, Date deadline) {
    log.info("{} legacy ERC20 account transfer job disabled; use collection_record/DB Asset Model flow address={}",
            currency.getName(), address);
  }

  @Override
  public void updateTotalCurrencyBalance() {
    log.info("updateTotalCurrencyBalance erc20 begin");
    trackedTokens.parallelStream().forEach((currency) -> {
      log.info("update {} total Balance begin", currency.getName());
      BigDecimal balance = getBalance(currency);
      updateTotalCurrencyBalance(currency, balance);
      log.info("update {} total Balance end", currency.getName());

    });

    log.info("updateTotalCurrencyBalance erc20 end");

  }


  public List<TransactionDTO> findRelatedTxs(Long height, Long bestHeight) {
    log.info("legacy ERC20 account log scanner disabled; use EvmDepositScanner, height={}", height);
    return Collections.emptyList();
  }

  @Override
  protected RuntimeAsset resolveRuntimeAsset(com.surprising.wallet.common.pojo.WithdrawRecord record) {
    return tokenByRuntimeId(record.getCurrency());
  }

  private RuntimeAsset tokenByRuntimeId(Integer runtimeCurrencyId) {
    if (runtimeCurrencyId == null) {
      throw new IllegalStateException("missing token runtime id from DB token configuration");
    }
    return trackedTokens.stream()
            .filter(token -> token.getIndex() == runtimeCurrencyId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "missing enabled token_config for runtime id " + runtimeCurrencyId));
  }

  @Override
  public RuntimeAsset getCurrency() {
    if (currency == null) {
      throw new IllegalStateException("missing enabled ERC20 token configuration");
    }
    return currency;
  }

  /**
   * 获得当前币种的精度，用于精度转换
   *
   * @return
   */
  @Override
  public BigDecimal getDecimal() {
    return getCurrency().getDecimal();
  }
}
