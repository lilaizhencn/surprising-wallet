package com.surprising.common.config;

import com.google.common.collect.Maps;

import java.util.Map;

/**
 * Created by scott on 2019/5/30.
 */
public class SAFETOKEN {
    private static final int DF = 9;
    private static final int CRL = 12;
    private static final int PXT = 14;
    private static final int BSTZ = 19;

    public static final String AssetId_DF = "348fccc25c210622e71992b49547aa7916a661f72daccd37e1b2fcbfe5340fca";//DF
    public static final String AssetId_CRL = "c7b22440f86040663354f1a3d4cb3e7079f2b008686604b1f7401eee155fa8c4";//CRL coral
    public static final String AssetId_PXT = "dc1884981a18260303737d0a492b12de69b7928d8711399faf2d84ce394379b7";//PXT
    public static final String AssetId_BSTZ = "73e1ff71f5f47c849a0b25b0e784590b056fccdc066a54b67daad75c501e717a";//BSTZ


    public static final Map<Integer, String> CONTRACT_SAFE = Maps.newConcurrentMap();
    public static final Map<Integer, Integer> CONTRACT_SAFE_DECIMALS = Maps.newConcurrentMap();

    static {
        CONTRACT_SAFE.put(DF, AssetId_DF);
        CONTRACT_SAFE.put(CRL, AssetId_CRL);
        CONTRACT_SAFE.put(PXT, AssetId_PXT);
        CONTRACT_SAFE.put(BSTZ, AssetId_BSTZ);
        CONTRACT_SAFE_DECIMALS.put(DF, 8);
        CONTRACT_SAFE_DECIMALS.put(CRL, 6);
        CONTRACT_SAFE_DECIMALS.put(PXT, 4);
        CONTRACT_SAFE_DECIMALS.put(BSTZ, 8);
    }


}
