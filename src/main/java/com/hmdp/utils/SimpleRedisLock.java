package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import io.lettuce.core.GeoArgs;
import jdk.internal.dynalink.beans.StaticClass;
import org.aspectj.apache.bcel.util.ClassPath;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private String name;

    private static final String KEY_PREFIX = "key:";

    private static final DefaultRedisScript<Long>  REDIS_SCRIPT ;

    static{
        REDIS_SCRIPT = new DefaultRedisScript<>();
        REDIS_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        REDIS_SCRIPT.setResultType(Long.TYPE);
    }


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        long threadId = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, ID_PREFIX + threadId, timeOutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unLock() {
        /**
         * 版本1
         */
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        if (!id.equals(ID_PREFIX + Thread.currentThread().getId()))
//            return;
//
//        stringRedisTemplate.delete(KEY_PREFIX + name);
        /**
         * 版本2
         */
        stringRedisTemplate.execute(REDIS_SCRIPT, Collections.singletonList(KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());

    }
}
