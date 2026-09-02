package com.rpa.server.common;

import java.util.List;

public class PageResult<T> {
    public long total;
    public long pages;
    public List<T> list;

    public static <T> PageResult<T> of(long total, long pageSize, List<T> list) {
        PageResult<T> p = new PageResult<>();
        p.total = total;
        p.pages = (total + pageSize - 1) / Math.max(pageSize, 1);
        p.list = list;
        return p;
    }
}
