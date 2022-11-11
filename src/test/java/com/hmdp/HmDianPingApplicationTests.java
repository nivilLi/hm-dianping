package com.hmdp;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker idWorker;
    @Resource
    RedissonClient redissonClient;

    @Resource
    StringRedisTemplate stringRedisTemplate;


    @Test
    void addCache(){
        shopService.saveShopRedis(1L, 20L);
    }

    @Test
    public  void test1() {
        LocalDateTime localDateTime = LocalDateTime.of(2022, 1, 1, 0, 0);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
    @Test
    void test2() throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(300);
        ExecutorService executorService = Executors.newFixedThreadPool(500);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                Long order = idWorker.nextId("order");
                System.out.println(order);
            }
            countDownLatch.countDown();
        };
        long currentTimeMillis1 = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long currentTimeMillis2 = System.currentTimeMillis();
        System.out.println(currentTimeMillis2 - currentTimeMillis1);
    }

    @Test
    void tets2(){
        RLock multiLock = redissonClient.getMultiLock();
        multiLock.tryLock();
    }

    @Test
    void addShopCache(){
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : collect.entrySet()) {

            Long typeId = entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(entry.getValue().size());
            for (Shop shop : list) {
                RedisGeoCommands.GeoLocation<String> geoLocation = new RedisGeoCommands.GeoLocation<String>(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
                geoLocations.add(geoLocation);
            }
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
        }
    }

}
