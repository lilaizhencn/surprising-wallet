package com.surprising.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * KeyPairVo
 *
 * @author lilaizhen
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class AddressInfo {
    private String address;
    private String priKey;
    private String pubKey;
}
