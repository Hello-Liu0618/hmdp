package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1767225600;

    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {//区分不同业务的Id
        //生成时间戳
        //获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeDiff = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        //获取当前日期
        String yyyyMMdd = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + yyyyMMdd);

        //拼接得到id

        return (timeDiff << COUNT_BITS) | count;
    }

    public static void main(String[] args) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        long second = baseTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
