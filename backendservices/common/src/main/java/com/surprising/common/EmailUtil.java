package com.surprising.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author lilaizhen
 */
public class EmailUtil {
    /**
     * 检查Email 格式（正则表达式）
     *
     * @param content email
     * @return boolean
     */
    public static boolean checkEmailFormat(String content) {
        /*
         * " \w"：匹配字母、数字、下划线。等价于'[A-Za-z0-9_]'。
         * "|"  : 或的意思，就是二选一
         * "*" : 出现0次或者多次
         * "+" : 出现1次或者多次
         * "{n,m}" : 至少出现n个，最多出现m个
         * "$" : 以前面的字符结束
         */
        String REGEX = "^\\w+((-\\w+)|(\\.\\w+))*@\\w+(\\.\\w{2,3}){1,3}$";
        Pattern p = Pattern.compile(REGEX);
        Matcher matcher = p.matcher(content);
        return matcher.matches();
    }
}
