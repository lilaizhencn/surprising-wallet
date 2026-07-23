package com.surprising.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

/**
 * @author lilaizhen
 */
public class UuidUtil {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public static String getUuid() {
        // [充值支付流水ID] 对应商户订单号=毫秒数 + 服务器IP尾号 + 线程ID + 5位随机数
        Random r = new Random();
        String ip = r.nextInt(1000) + "";

        String hostAddress;
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            hostAddress = "127.0.0.1";
        }

        if (hostAddress != null) {
            String[] split = hostAddress.split("\\.");
            ip = split[split.length - 1];
        }
        return LocalDateTime.now().format(DATE_TIME_FORMATTER) + Thread.currentThread().getId() + ip + r.nextInt(100000);
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
