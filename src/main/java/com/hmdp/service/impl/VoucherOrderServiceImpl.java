package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> REDIS_SCRIPT ;
//java阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();


    static{
        REDIS_SCRIPT = new DefaultRedisScript<>();
        REDIS_SCRIPT.setLocation(new ClassPathResource("skillvoucher.lua"));
        REDIS_SCRIPT.setResultType(Long.TYPE);
    }

    @PostConstruct
    public void init(){
        seckillOrderExecutor.submit(() -> {
            while (true){
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
                    if (records == null ||  records.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> entries = records.get(0);
                    Map<Object, Object> entriesValue = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entriesValue, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders" ,"g1", entries.getId());
                } catch (Exception e) {
                    log.error("订单异常");
                    handlePendingList();
                }
            }
        });
    }

    public void handlePendingList() throws InterruptedException {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0")));
                if (records == null || records.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> entries = records.get(0);
                Map<Object, Object> entriesValue = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entriesValue, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", entries.getId());
            } catch (Exception e) {
                Thread.sleep(2000);
                log.error("订单异常");
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock("lock:order" + voucherOrder.getUserId());
        boolean tryLock = lock.tryLock();
        if (!tryLock){
            log.error("重复下单");
            return;
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
             proxy.createOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }

    @Override
    public Result seckillVoucher(Long voucherId) {


//        旧版查数据库判断
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("未到抢购时间");
//        }
//
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//
//        if(seckillVoucher.getStock() < 1){
////            return Result.fail("库存不足");
////        }
////        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
////        if (!success){
////            return Result.fail("库存不足");
//        }


//        新版查redis判断，调lua脚本
//        Long userId = UserHolder.getUser().getId();
//        Long orderId = redisIdWorker.nextId("order");
//        Long success = stringRedisTemplate.execute(REDIS_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId));
//
//        switch (success.intValue()) {
//            case 2:
//                return Result.fail("重复下单");
//            case 1:
//                return Result.fail("库存不足");
//        }
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//
//        orderTasks.add(voucherOrder);
//
//        return Result.ok(orderId);

//        采用消息队列进行秒杀优化
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long success = stringRedisTemplate.execute(REDIS_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId));

        switch (success.intValue()) {
            case 2:
                return Result.fail("重复下单");
            case 1:
                return Result.fail("库存不足");
        }
        return Result.ok(orderId);
/**
 * 自定义锁
 */
//        SimpleRedisLock lock = new SimpleRedisLock( stringRedisTemplate, "order");
    }

    @Transactional
    public void createOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();

        Long voucherId = voucherOrder.getVoucherId();
        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count == 1) {
            return ;
        }
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success){
            return ;
        }
//        VoucherOrder voucherOrder = new VoucherOrder();
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
    }

}
