package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);

    }

    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public   <R, F> R dealCacheThrough
            (F id, Class<R> clazz, String keyPrefix, Function<F, R> function, Long time, TimeUnit timeUnit) throws InterruptedException {
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StringUtils.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, clazz);
            return r;
        }
        if (json.equals("")) {
            return null;
        }
        R r = function.apply(id);
        if (r == null) {
            this.set(keyPrefix + id, "", time, timeUnit);
            return null;
        }
        json = JSONUtil.toJsonStr(r);
        this.set(keyPrefix + id, json, time, timeUnit);
        return r;
    }

    private boolean lock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, F> R  queryWithLogicExpire
            (F id, Class<R> clazz, String keyPrefix, Function<F, R> function, Long time, TimeUnit timeUnit) throws InterruptedException {

        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Object data = redisData.getData();
        R r = JSONUtil.toBean((JSONObject) data, clazz);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        boolean lock = this.lock(RedisConstants.LOCK_SHOP_KEY + id);
        if (lock){
            json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
            redisData = JSONUtil.toBean(json, RedisData.class);
            data = redisData.getData();
            r = JSONUtil.toBean((JSONObject) data, clazz);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopRedis(keyPrefix, id, function, time, timeUnit);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        return r;
    }

    public <R, F> void saveShopRedis(String keyPrefix, F id, Function<F, R> function, Long time, TimeUnit timeUnit){
        R r = function.apply(id);
        setWithLogicExpire(keyPrefix + id, r, time, timeUnit);
    }

}
