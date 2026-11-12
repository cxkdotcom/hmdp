package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //方法二得到的时间戳
    private static final long START_TIMESTAMP = 1794009600;
    //此工具用于生成全局唯一的id
    public Long nextId(String prefixkey) {
        //1.获取想要的时间戳（不能用当前时间，否则会被用户猜出来）
        //1.1获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        //1.2计算时间戳差值得到想要的时间戳
        long timestamp = epochSecond - START_TIMESTAMP;
        //2.获取序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr" + prefixkey + ":" + date);
        //组合成id
        return timestamp <<32 | count;
    }

    //读取初始时间的时间戳
    public static void main(String[] args) {
        LocalDateTime localDateTime = LocalDateTime.of(2026, 11, 7, 0, 0, 0);
        long startTimestamp = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(startTimestamp);
    }
}
