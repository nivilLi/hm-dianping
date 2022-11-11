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
    private static final long BASE_TIME_STAMP =  1640995200L;
    public Long nextId(String key){
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BASE_TIME_STAMP;
        String date = now.format(DateTimeFormatter.ofPattern("yy:MM::dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + key + ":" + date);
        return timeStamp << 32 | count;

    }

}
