package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */
@AllArgsConstructor
public enum UserTypeEnum {
    /**
     *
     */
    NORMAL(1, "普通"),
    VIP(2, "VIP"),
    ENTERPRISE(3, "企业"),
    ROBOT(4, "机器人"),
    STAFF(5, "员工"),
    OTHER(6, "其他"),
    ;
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}
