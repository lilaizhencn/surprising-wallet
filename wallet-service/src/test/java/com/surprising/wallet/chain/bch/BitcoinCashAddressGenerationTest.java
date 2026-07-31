package com.surprising.wallet.chain.bch;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;import com.surprising.wallet.sdk.bitcoinj.bitcoincash.*;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;import org.bitcoinj.base.LegacyAddress;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * 验证 {@code BitcoinCashAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class BitcoinCashAddressGenerationTest{
 /**
  * 保存 {@code X}，用于承载当前测试夹具的配置或运行数据。
  */
 static final String[] X={
"tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB",
"tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT",
"tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4"};
 /**
  * 验证 {@code generatesCashAddrAndLegacyCompatibility} 对应的测试场景，明确输入、预期结果和异常边界。
  */
 @Test void generatesCashAddrAndLegacyCompatibility(){
  String deposit=at(9201,1,0),collection=at(9202,1,0),hot=at(0,0,0);
  assertTrue(deposit.startsWith("bchtest:p"));assertNotEquals(deposit,collection);
  System.out.println("BCH_TESTNET_DEPOSIT_ADDRESS="+deposit);
  System.out.println("BCH_TESTNET_COLLECTION_ADDRESS="+collection);
  System.out.println("BCH_TESTNET_HOT_ADDRESS="+hot);
 }
 /**
  * 验证 {@code at} 对应的测试场景，明确输入、预期结果和异常边界。
  */
 static String at(int user,int biz,int index){
  var p=BitcoinCashNetworkParameters.testnet();var g=new LegacyMultiSignAddressGenerator();
  for(String x:X)g.addECKey(Bip32Node.decode(x).getChild(44).getChild(145).getChild(biz).getChild(user).getChild(index).getEcKey());
  String legacy=g.generateAddress(p,2);return BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromBase58(p,legacy),p.cashPrefix());
 }
}
