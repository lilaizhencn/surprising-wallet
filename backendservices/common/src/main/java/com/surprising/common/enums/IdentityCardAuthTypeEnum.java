package com.surprising.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lilaizhen
 */
@AllArgsConstructor
public enum IdentityCardAuthTypeEnum {
    /**
     *
     */
    ID_CARD(1, "身份证"),
    PASSPORT(2, "护照"),
    OTHER(3, "其他");
    @Setter
    @Getter
    private int status;

    @Setter
    @Getter
    private String name;
}
