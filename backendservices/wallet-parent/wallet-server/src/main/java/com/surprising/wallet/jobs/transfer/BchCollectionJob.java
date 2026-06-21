package com.surprising.wallet.jobs.transfer;
import com.alibaba.fastjson.JSONObject;import com.surprising.common.mybatis.pager.PageInfo;import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;import com.surprising.wallet.common.currency.CurrencyEnum;import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.utils.Constants;import com.surprising.wallet.sdk.bitcoinj.bitcoincash.*;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;import com.surprising.wallet.service.criteria.*;
import com.surprising.wallet.service.dao.ChainJdbcRepository;import com.surprising.wallet.service.service.*;
import org.springframework.beans.factory.annotation.Value;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;import java.time.Instant;import java.util.*;
@Component public class BchCollectionJob{
 final AddressService addresses;final UtxoTransactionService utxos;final WithdrawTransactionService txs;final ChainJdbcRepository repo;
 @Value("${atomex.wallet.collection.enabled-currencies:}")String enabled;@Value("${atomex.wallet.hot.user-id:0}")Long hotUser;@Value("${atomex.wallet.hot.biz:0}")Integer hotBiz;@Value("${atomex.wallet.hot.address-index:0}")Integer hotIndex;
 public BchCollectionJob(AddressService a,UtxoTransactionService u,WithdrawTransactionService t,ChainJdbcRepository r){addresses=a;utxos=u;txs=t;repo=r;}
 @Scheduled(cron="24/30 * * * * ?")@Transactional(rollbackFor=Throwable.class)public void execute(){
  if(Arrays.stream(enabled.split(",")).map(String::trim).noneMatch(v->"*".equals(v)||"bch".equalsIgnoreCase(v)))return;
  CurrencyEnum c=CurrencyEnum.BCH;ShardTable table=ShardTable.builder().prefix("bch").build();
  AddressExample ae=new AddressExample();ae.createCriteria().andUserIdEqualTo(hotUser).andBizEqualTo(hotBiz).andIndexEqualTo(hotIndex);Address hot=addresses.getOneByExample(ae,table).orElse(null);if(hot==null)return;
  UtxoTransactionExample ue=new UtxoTransactionExample();ue.createCriteria().andStatusEqualTo((byte)0).andSpentEqualTo((byte)0).andSpentTxIdEqualTo(Constants.UNSPENT_TX_ID).andConfirmNumGreaterThanOrEqualTo(c.getDepositConfirmNum());
  PageInfo p=new PageInfo();p.setPageSize(10);p.setStartIndex(0);p.setSortItem("id");p.setSortType(PageInfo.SORT_TYPE_ASC);
  List<UtxoTransaction> list=utxos.getByPage(p,ue,table).stream().filter(u->{Address a=addresses.getAddress(u.getAddress(),table);return a!=null&&a.getUserId()>0;}).toList();if(list.isEmpty())return;
  List<Address> ins=new ArrayList<>();BigDecimal amount=BigDecimal.ZERO;for(var u:list){ins.add(addresses.getAddress(u.getAddress(),table));amount=amount.add(u.getBalance());}
  long atomic=amount.multiply(c.getDecimal()).longValueExact(),fee=P2shMultisigFeeCalculator.estimateBytes(list.size(),1,2,3),out=atomic-fee;if(out<BitcoinCashFeePolicy.DUST_THRESHOLD_SAT)return;
  Date now=Date.from(Instant.now());String id="bch-collection-"+list.get(0).getTxId()+"-"+list.get(0).getSeq();BigDecimal output=BigDecimal.valueOf(out).divide(c.getDecimal()),feeAmount=BigDecimal.valueOf(fee).divide(c.getDecimal());
  WithdrawRecord wr=WithdrawRecord.builder().withdrawId(id).txId("collection").address(hot.getAddress()).userId(hotUser).balance(output).currency(c.getIndex()).biz(hotBiz).fee(feeAmount).status((byte)1).createDate(now).updateDate(now).build();
  JSONObject s=new JSONObject();s.put("type","collection");s.put("collectionId",id);s.put("utxos",list);s.put("addresses",ins);s.put("withdraw",List.of(wr));s.put("feeRate",1);s.put("totalAmount",amount.toPlainString());
  WithdrawTransaction tx=WithdrawTransaction.builder().balance(amount).currency(c.getIndex()).status((short)1).txId("signing").signature(s.toJSONString()).createDate(now).updateDate(now).build();txs.add(tx,table);
  repo.createCollectionRecord(id,"BCH","BCH",ins.get(0).getAddress(),hot.getAddress(),output,feeAmount,s.toJSONString());
  for(var u:list)if(repo.lockUtxo("BCH",u.getTxId(),u.getSeq(),tx.getId().toString())!=1)throw new IllegalStateException("BCH UTXO lock failed");
  repo.updateCollectionStatus("BCH",id,"SIGNING",null,null,s.toJSONString());
  List<UtxoTransaction> spent=list.stream().map(u->UtxoTransaction.builder().id(u.getId()).spent((byte)1).spentTxId(tx.getId().toString()).status((byte)1).updateDate(now).build()).toList();utxos.batchEdit(spent,table);
  REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,JSONObject.toJSONString(tx));
 }
}
