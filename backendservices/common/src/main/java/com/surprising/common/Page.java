package com.surprising.common;

import lombok.Data;

/**
 * @author lilaizhen
 */
@Data
public class Page {
    Integer start = 1;
    /**
     * 每页多少条
     */
    Integer size = 20;
    /**
     * 共多少条
     */
    Integer total = 0;


    String orderBy = "desc";
}
