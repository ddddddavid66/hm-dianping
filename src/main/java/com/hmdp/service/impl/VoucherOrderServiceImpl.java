package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import java.util.concurrent.*;

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
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Resource
    @Lazy
    private IVoucherOrderService proxy;

    private static final DefaultRedisScript SECKILL_SCRIPT;
    //线程池
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(long.class);
    }

    //初始化阻塞队列
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);

    // 注解 使类启动就执行任务
    @PostConstruct
    public void init() {
        handleException();
        EXECUTOR_SERVICE.submit(new voUcherkillOrder());
    }

    // 创建线程启动 任务  现在不需要 阻塞队列 需要
    private class voUcherkillOrder implements Runnable {
        @Override
        public void run() {
            while (true) {
                try{
                // 获取消息队列 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> msg = redisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2L)),
                        StreamOffset.create(RedisConstants.STREAM_ORDER_KEY, ReadOffset.lastConsumed())
                );
                // 获取失败 直接continue
                if(msg == null || msg.isEmpty()){
                    handleException();
                    continue;
                }
                    // 获取成功
                    MapRecord<String, Object, Object> entries = msg.get(0); // ID 和 field:value
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                    // 处理下单
                    boolean flag = handlerVoucherOrder(voucherOrder);
                    if(flag){
                        //ACK确认
                        redisTemplate.opsForStream().acknowledge(RedisConstants.STREAM_ORDER_KEY,"g1", entries.getId());
                    }
                } catch (Exception e) {
                   handleException();
                }
            }
        }
    }

    public  void handleException() {
        //抛异常 进入pending list取出
        while (true) {
            // 获取消息队列 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
            List<MapRecord<String, Object, Object>> msg = redisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(RedisConstants.STREAM_ORDER_KEY, ReadOffset.from("0"))
            );
            // 获取失败 直接continue
            if (msg == null || msg.isEmpty()) {
                break;
            }
            try {
                // 获取成功
                MapRecord<String, Object, Object> entries = msg.get(0); // ID 和 field:value
                Map<Object, Object> values = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                // 处理下单
                boolean flag = handlerVoucherOrder(voucherOrder);
                if(flag){
                    //ACK确认
                    redisTemplate.opsForStream().acknowledge(RedisConstants.STREAM_ORDER_KEY, "g1", entries.getId());
                }
            } catch (Exception e2) {
                //抛异常 continue
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                continue;
            }
        }
    }

    /*// 创建线程启动 任务  现在不需要 阻塞队列 需要
    private class voUcherkillOrder implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTask.take();
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("创建订单失败");
                }
            }
        }
    }*/

    public boolean handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id
        Long userId = voucherOrder.getUserId();
        // 创建锁
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean flag = lock.tryLock();
        // 判断锁
        if (!flag) {
            log.error("获取锁失败");
            return false;
        }
        // 创建订单
        try {
            proxy.createOrderNew(voucherOrder);
        } finally {
            lock.unlock();  // 释放锁
        }
        return true;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本        自动 存入redis 消息队列
        long result = (long) redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),String.valueOf(orderId));
        //判断结果 不是 0 返回异常
        if (result != 0) {
            //返回异常
            if (result == 1) {
                return Result.fail("库存数量不足");
            } else {
                return Result.fail("用户以及下单");
            }
        }
       /* //  获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();*/
        //返回订单id
        return Result.ok(voucherId);
    }



    public Result seckillVoucherOld(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Integer result = (Integer) redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //判断结果 不是 0 返回异常
        if (result != 0) {
            //返回异常
            if (result == 1) {
                return Result.fail("库存数量不足");
            } else {
                return Result.fail("用户以及下单");
            }
        }

        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder().id(orderId).userId(userId)
                .voucherId(seckillVoucher.getVoucherId()).build();
        //  存入 阻塞队列
        orderTask.add(voucherOrder);
        //  获取代理对象
        //返回订单id
        return Result.ok(voucherId);
    }


    public Result seckillVoucherOld2(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //秒杀 未开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        //超时
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀结束");
        }
        //判断库存
        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("没有库存");
        }
        Long userId = UserHolder.getUser().getId();
        //获取锁
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        //boolean flag = lock.tryLock(1,RedisConstants.LOCK_ORDER_TTL, TimeUnit.SECONDS);
        boolean flag = lock.tryLock();
        /*SimpleRedisLock redisLock = new SimpleRedisLock("order" + userId, redisTemplate);
        boolean flag = redisLock.tryLock(5);*/
        if (!flag) { //获取失败
            return Result.fail("不允许重复下单");
        }
        //获取成功
        try {
            return proxy.createOrder(seckillVoucher, voucherId, userId);
        } finally {
            //redisLock.unLock();
            lock.unlock();
        }
    }

    @Transactional
    public Result createOrder(SeckillVoucher seckillVoucher, Long voucherId, Long userId) {
        //1 订单id
        //2 用户id
        long orderId = redisIdWorker.nextId("order");
        //一人一单
        //1 查询数据
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count != 0) {
            //买过了
            return Result.fail("用户已经买过一次了");
        }
        //扣除库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)//加上乐观锁
                .update();
        if (!flag) {
            return Result.fail("没有库存");
        }

        // 创建订单
        VoucherOrder voucherOrder = VoucherOrder.builder().id(orderId).userId(userId)
                .voucherId(seckillVoucher.getVoucherId()).build();
        //保存订单
        save(voucherOrder);
        return Result.ok(orderId);
    }


    @Transactional
    public void createOrderNew(VoucherOrder voucherOrder) {
        //1 订单id
        //2 用户id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //一人一单
        //1 查询数据
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count != 0) {
            //买过了
            log.error("用户已经买过一次了");
            return;
        }
        //扣除库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)//加上乐观锁
                .update();
        if (!flag) {
            log.error("没有库存");
            return;
        }
        //保存订单
        save(voucherOrder);
    }
}







