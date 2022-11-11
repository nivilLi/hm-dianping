package com.hmdp.service.impl;

import cn.hutool.core.lang.copier.SrcToDestCopier;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import com.sun.xml.internal.ws.policy.privateutil.PolicyUtils;
import jodd.util.StringUtil;
import org.omg.PortableServer.THREAD_POLICY_ID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

     @Resource
     private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) throws InterruptedException {
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StringUtils.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if ("".equals(shopJson)) {
            return null;
        }
        try {
            boolean lock = this.lock(RedisConstants.LOCK_SHOP_KEY + id);
            if (!lock){
                Thread.sleep(1000);
               return queryWithMutex(id);
            }
            shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if (StringUtils.isNotBlank(shopJson)) {
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            if ("".equals(shopJson)) {
                return null;
            }
            Shop shop = this.getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
                return null;
            }
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, shopJson, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
            return shop;
        } catch (InterruptedException e) {
            throw  new RuntimeException(e);
        } finally {
            this.unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }
    }

    /**
     * 查数据库转入Redis
     * @param id
     * @param expireSeconds
     */
    public void saveShopRedis(Long id, Long expireSeconds){
        Shop shop = this.getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    public Shop queryWithLogicExpire(Long id) throws InterruptedException {
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        if (StringUtils.isBlank(shopJson)) {
//            return null;
//        }
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Object data = redisData.getData();
//        Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
//        if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
//            return shop;
//        }
//        boolean lock = this.lock(RedisConstants.LOCK_SHOP_KEY + id);
//        if (lock){
//            shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//             redisData = JSONUtil.toBean(shopJson, RedisData.class);
//             data = redisData.getData();
//             shop = JSONUtil.toBean((JSONObject) data, Shop.class);
//            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
//                return shop;
//            }
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    saveShopRedis(id, 30L);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                } finally {
//                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
//                }
//            });
//        }
//        return shop;
        return cacheClient.queryWithLogicExpire
                (id, Shop.class, RedisConstants.CACHE_SHOP_KEY
                        , this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop == null){
            Result.fail("无此店铺");
        }
        this.update(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok("更新完成");
    }

    private boolean lock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private Shop dealCacheThrough(Long id) throws InterruptedException {
            return cacheClient.dealCacheThrough
                    (id, Shop.class, RedisConstants.CACHE_SHOP_KEY
                    , this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        if (StringUtils.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if (shopJson.equals("")) {
//            return null;
//        }
//        Shop shop = this.getById(id);
//        if (shop == null) {
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
//            return null;
//        }
//        shopJson = JSONUtil.toJsonStr(shop);
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, shopJson, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
//        return shop;
//    }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null) {
            // 根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int begin = current - 1 * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= begin){
            return Result.ok(Collections.emptyList());
        }
        ArrayList<Long> ids = new ArrayList<>();
        HashMap<String, Distance> distanceHashMap = new HashMap<>();
        content.stream().skip(begin).forEach(item ->{
            ids.add(Long.valueOf(item.getContent().getName()));
            distanceHashMap.put(item.getContent().getName(), item.getDistance());
        });
        String join = StringUtil.join(ids, ",");
        List<Shop> shops = this.query().in("id", ids).last("order by field( id," + join + " )").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceHashMap.get(shop.getId().toString()).getValue());
        }
         return Result.ok(shops);
    }
}
