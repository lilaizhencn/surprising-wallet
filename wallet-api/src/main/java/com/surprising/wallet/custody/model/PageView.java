package com.surprising.wallet.custody.model;

import java.util.List;

/**
 * Console/Platform 列表统一分页响应。
 */
public record PageView<T>(List<T> items, long total, int limit, int offset) {
    public PageView {
        items = List.copyOf(items);
        total = Math.max(total, 0L);
        limit = Math.max(limit, 1);
        offset = Math.max(offset, 0);
    }
}
