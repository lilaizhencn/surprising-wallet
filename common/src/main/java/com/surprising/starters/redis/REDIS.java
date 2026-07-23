package com.surprising.starters.redis;

import com.alibaba.fastjson.JSON;
import com.surprising.starters.redis.exception.LettuceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author lilaizhen
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@Slf4j
public class REDIS {

    private static LettuceConnectionFactory factory;

    @PostConstruct
    public void init() {
        factory = lettuceConnectionFactory;
    }

    private static final int TIMES = 3;
    @Autowired
    private LettuceConnectionFactory lettuceConnectionFactory;

    public static String get(String key) {
        log.debug("get begin: {}", key);
        byte[] keyBytes = encode(key);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.get(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("get error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("get end: {}", result);
        return result;
    }

    public static Integer getInt(String key) {
        log.debug("getInt begin: {}", key);

        byte[] keyBytes = encode(key);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.get(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("getInt error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Integer result = null;
        if (resultBytes != null) {
            String resultStr = encode(resultBytes);
            result = Integer.valueOf(resultStr);
        }
        log.debug("getInt end: {}", result);
        return result;
    }

    public static Boolean exists(String key) {
        log.debug("exists begin: {}", key);

        byte[] keyBytes = encode(key);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.exists(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("exists error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("exists end: {}", result);
        return result;
    }

    public static Long incr(String key) {
        log.debug("incr begin: {}", key);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.incr(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("incr error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("incr end: {}", result);
        return result;
    }

    public static Long incrBy(String key, long increment) {
        log.debug("incrby begin: {} {}", key, increment);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.incrBy(keyBytes, increment);
                    break;
                } catch (Throwable e) {
                    log.error("incr error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("incrby end: {}", result);
        return result;
    }

    public static void set(String key, String value) {
        log.debug("set begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    connection.set(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("set error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("set end");
    }

    public static void set(String key, int value) {
        log.debug("set begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(String.valueOf(value));
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    connection.set(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("set error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("set end");
    }

    public static void setEx(String key, long time, int value) {
        log.debug("setEx begin: {}, {}, {}", key, time, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(String.valueOf(value));
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    connection.setEx(keyBytes, time, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("setEx error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("setEx end");
    }

    public static void setEx(String key, long time, String value) {
        log.debug("setEx begin: {}, {}, {}", key, time, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    connection.setEx(keyBytes, time, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("setEx error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("setEx end");
    }

    public static boolean setNX(String key, String value) {
        log.debug("setNX begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.setNX(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("setNX error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("setNX end: {}", result);
        return result == null ? false : result;
    }

    public static Integer getSet(String key, int value) {
        log.debug("getSet begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(String.valueOf(value));
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.getSet(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("getSet error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Integer result = null;
        if (resultBytes != null) {
            String resultStr = encode(resultBytes);
            result = Integer.valueOf(resultStr);
        }
        log.debug("getSet end: {}", result);
        return result;
    }

    public static String getSet(String key, String value) {
        log.debug("getSet begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.getSet(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("getSet error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("getSet end: {}", result);
        return result;
    }

    public static boolean expire(String key, long seconds) {
        log.debug("expire begin: {}, {}", key, seconds);

        byte[] keyBytes = encode(key);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.expire(keyBytes, seconds);
                    break;
                } catch (Throwable e) {
                    log.error("expire error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("expire end: {}", result);
        return result == null ? false : result;
    }

    /**
     * 当 key 不存在时，返回 -2
     * <p/>
     * 当 key 存在但没有设置剩余生存时间时，返回 -1
     */
    public static Long ttl(String key) {
        log.debug("ttl begin: {}", key);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.ttl(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("ttl error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("ttl end: {}", result);
        return result;
    }

    public static Long del(String key) {
        log.debug("del begin: {}", key);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.del(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("del error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("del end: {}", result);
        return result;
    }

    public static Long lPush(String key, String value) {
        log.debug("lPush begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.lPush(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("lPush error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("lPush end: {}", result);
        return result;
    }

    public static String lPop(String key) {
        log.debug("lPop begin: {}", key);

        byte[] keyBytes = encode(key);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.lPop(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("lPop error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("lPop end: {}", result);
        return result;
    }

    public static String rPop(String key) {
        log.debug("rPop begin: {}", key);

        byte[] keyBytes = encode(key);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.rPop(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("rPop error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("rPop end: {}", result);
        return result;
    }


    public static String rPoplPush(String src, String dst) {
        log.debug("rPoplPush begin, src: {},dst:{}", src, dst);

        byte[] srcBytes = encode(src);
        byte[] dstBytes = encode(dst);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.rPopLPush(srcBytes, dstBytes);
                    break;
                } catch (Throwable e) {
                    log.error("rPoplPush error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("rPoplPush end: {}", result);
        return result;
    }

    public static List<String> lRange(String key, long start, long end) {
        log.debug("lRange begin: {}", key);

        byte[] keyBytes = encode(key);
        List<byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.lRange(keyBytes, start, end);
                    break;
                } catch (Throwable e) {
                    log.error("lRange error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        List<String> result = new LinkedList<>();
        for (byte[] bytes : resultBytes) {
            String s = encode(bytes);
            result.add(s);
        }
        log.debug("lRange end: {}", result.size());
        return result;
    }

    public static Long rPush(String key, String value) {
        log.debug("rPush begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.rPush(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("rPush error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("rPush end: {}", result);
        return result;
    }

    public static Long lRem(String key, long countParams, String value) {
        log.debug("lRem begin: {}, {}, {}", key, countParams, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.lRem(keyBytes, countParams, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("lRem error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("lRem end: {}", result);
        return result;
    }

    public static Long lLen(String key) {
        log.debug("lLen begin: {}", key);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.lLen(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("lLen error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("lLen end: {}", result);
        return result;
    }

    public static void lTrim(String key, long start, long end) {
        log.debug("lTrim begin: {} {} {}", key, start, end);

        byte[] keyBytes = encode(key);
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    connection.lTrim(keyBytes, start, end);
                    break;
                } catch (Throwable e) {
                    log.error("lTrim error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("lTrim end");
    }

    public static Long sAdd(String key, String value) {
        log.debug("sAdd begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.sAdd(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("sAdd error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("sAdd end: {}", result);
        return result;
    }

    public static Long sRem(String key, String value) {
        log.debug("sRem begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.sRem(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("sRem error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("sRem end: {}", result);
        return result;
    }

    public static Set<String> sMembers(String key) {
        log.debug("sMembers begin: {}", key);

        byte[] keyBytes = encode(key);
        Set<byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.sMembers(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("sMembers error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Set<String> result = null;
        if (resultBytes != null) {
            result = new HashSet<>();
            for (byte[] r : resultBytes) {
                if (r != null) {
                    String rStr = encode(r);
                    result.add(rStr);
                }
            }
        }
        log.debug("sMembers end: {}", result == null ? null : result.size());
        return result;
    }

    public static boolean sIsMember(String key, String value) {
        log.debug("sIsMember begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.sIsMember(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("sIsMember error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("sIsMember end: {}", result);
        return result == null ? false : (boolean) result;
    }

    public static boolean zAdd(String key, double score, String value) {
        log.debug("zAdd begin: {}, {}, {}", key, score, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.zAdd(keyBytes, score, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("zAdd error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("zAdd end: {}", result);
        return result == null ? false : (boolean) result;
    }

    public static Long zRem(String key, String value) {
        log.debug("zRem begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.zRem(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("zRem error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("zRem end: {}", result);
        return result;
    }

    public static Long zRemRangeByScore(String key, double min, double max) {
        log.debug("zRemRangeByScore begin: {}, {}, {}", key, min, max);
        Long result = zRemRangeByScore(key, Range.closed(min, max));
        log.debug("zRemRangeByScore end");
        return result;
    }

    public static Long zRemRangeByScore(String key, Range range) {
        log.debug("zRemRangeByScore begin: {}, {}", key, JSON.toJSON(range));

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.zRemRangeByScore(keyBytes, range);
                    break;
                } catch (Throwable e) {
                    log.error("zRemRangeByScore error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("zRemRangeByScore end: {}", result);
        return result == null ? 0 : (long) result;
    }

    public static Set<String> zRange(String key, long start, long end) {
        log.debug("zRange begin: {}, {}, {}", key, start, end);

        byte[] keyBytes = encode(key);
        Set<byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.zRange(keyBytes, start, end);
                    break;
                } catch (Throwable e) {
                    log.error("zRange error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Set<String> result = null;
        if (resultBytes != null) {
            result = new HashSet<>();
            for (byte[] r : resultBytes) {
                if (r != null) {
                    String rStr = encode(r);
                    result.add(rStr);
                }
            }
        }
        log.debug("zRange end: {}", result == null ? null : result.size());
        return result;
    }

    public static Set<String> zRangeByScore(String key, double min, double max) {
        log.debug("zRangeByScore begin: {}, {}, {}", key, min, max);
        Set<String> result = zRangeByScore(key, Range.closed(min, max));
        log.debug("zRangeByScore end");
        return result;
    }

    public static Set<String> zRangeByScore(String key, Range range) {
        log.debug("zRangeByScore begin: {}, {}", key, JSON.toJSON(range));

        byte[] keyBytes = encode(key);
        Set<byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.zRangeByScore(keyBytes, range);
                    break;
                } catch (Throwable e) {
                    log.error("zRangeByScore error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Set<String> result = null;
        if (resultBytes != null) {
            result = new HashSet<>();
            for (byte[] r : resultBytes) {
                if (r != null) {
                    String rStr = encode(r);
                    result.add(rStr);
                }
            }
        }
        log.debug("zRangeByScore end: {}", result == null ? null : result.size());
        return result;
    }

    public static String hGet(String key, String value) {
        log.debug("hGet begin: {}, {}", key, value);

        byte[] keyBytes = encode(key);
        byte[] valueBytes = encode(value);
        byte[] resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.hGet(keyBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("hGet error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        String result = null;
        if (resultBytes != null) {
            result = encode(resultBytes);
        }
        log.debug("hGet end: {}", result);
        return result;
    }

    public static long hLen(String key) {
        log.debug("hLen begin: {}", key);

        byte[] keyBytes = encode(key);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.hLen(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("hLen error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("hLen end: {}", result);
        return result == null ? 0 : (long) result;
    }

    public static Map<String, String> hGetAll(String key) {
        log.debug("hGetAll begin: {}", key);

        byte[] keyBytes = encode(key);
        Map<String, String> result = null;
        Map<byte[], byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.hGetAll(keyBytes);
                    break;
                } catch (Throwable e) {
                    log.error("hGetAll error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        if (resultBytes != null) {
            result = new HashMap<>();
            for (Map.Entry<byte[], byte[]> entry : resultBytes.entrySet()) {
                byte[] k = entry.getKey();
                String kStr = null;
                if (k != null) {
                    kStr = encode(k);
                }
                byte[] v = entry.getValue();
                String vStr = null;
                if (v != null) {
                    vStr = encode(v);
                }
                result.put(kStr, vStr);
            }
        }
        log.debug("hGetAll end: {}", result == null ? null : result.size());
        return result;
    }

    public static boolean hSet(String key, String field, String value) {
        log.debug("hSet begin: {}, {}, {}", key, field, value);

        byte[] keyBytes = encode(key);
        byte[] fieldBytes = encode(field);
        byte[] valueBytes = encode(value);
        Boolean result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.hSet(keyBytes, fieldBytes, valueBytes);
                    break;
                } catch (Throwable e) {
                    log.error("hSet error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("hSet end: {}", result);
        return result == null ? false : (boolean) result;
    }

    public static long hDel(String key, String field) {
        log.debug("hDel begin: {}, {}", key, field);

        byte[] keyBytes = encode(key);
        byte[] fieldBytes = encode(field);
        Long result = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    result = connection.hDel(keyBytes, fieldBytes);
                    break;
                } catch (Throwable e) {
                    log.error("hDel error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        log.debug("hDel end: {}", result);
        return result == null ? 0 : (long) result;
    }

    public static Set<String> keys(String pattern) {
        log.debug("keys begin: {}", pattern);

        byte[] patternBytes = encode(pattern);
        Set<byte[]> resultBytes = null;
        RedisConnection connection = factory.getConnection();
        try {
            int count = 0;
            while (true) {
                count++;
                try {
                    resultBytes = connection.keys(patternBytes);
                    break;
                } catch (Throwable e) {
                    log.error("keys error: ", e);
                    if (count > TIMES) {
                        throw new LettuceException(e);
                    }
                }
            }
        } finally {
            connection.close();
        }
        Set<String> result = null;
        if (resultBytes != null) {
            result = new HashSet<>();
            for (byte[] r : resultBytes) {
                if (r != null) {
                    String rStr = encode(r);
                    result.add(rStr);
                }
            }
        }
        log.debug("keys end: {}", result == null ? "null" : result.size());
        return result;
    }

    public static byte[] encode(String str) {
        if (str == null) {
            throw new LettuceException("value sent to redis cannot be null");
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static String encode(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }


}
