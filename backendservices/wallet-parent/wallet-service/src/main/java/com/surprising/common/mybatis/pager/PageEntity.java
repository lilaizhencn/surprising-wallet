package com.surprising.common.mybatis.pager;

import com.surprising.common.mybatis.service.CrudService;
import lombok.Data;

import java.util.List;

@Data
public class PageEntity {
    int pageTotal;
    int pageNum;
    int pageSize;
    List data;

    public static <B, E, L> PageEntity getPage(final CrudService<B, E, L> service, final int pageNum, final int pageSize, final E example) {

        return PageEntity.getPage(service,  pageNum, pageSize, "id", example);
    }

    public static <B, E, L> PageEntity getPage(final CrudService<B, E, L> service,  final int pageNum, int pageSize, final String sortItem, final E example) {
        int startIndex = (pageNum - 1) * pageSize;
        startIndex = startIndex >= 0 ? startIndex : 0;
        pageSize = pageSize >= 0 ? pageSize : 10;
        final PageInfo pageInfo = new PageInfo();
        pageInfo.setSortItem(sortItem);
        pageInfo.setPageSize(pageSize);
        pageInfo.setStartIndex(startIndex);
        pageInfo.setSortType(PageInfo.SORT_TYPE_DES);
        final List<B> recordList = service.getByPage(pageInfo, example);
        final PageEntity pageEntity = new PageEntity();
        pageEntity.setData(recordList);
        Long total = pageInfo.getTotals();
        final int pageTotal = (total.intValue() + pageSize - 1) / pageSize;
        pageEntity.setPageNum(pageNum);
        pageEntity.setPageSize(pageSize);
        pageEntity.setPageTotal(pageTotal);
        return pageEntity;
    }
}
