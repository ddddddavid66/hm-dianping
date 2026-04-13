package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //秒杀 未开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        //超时
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀结束");
        }
        //判断库存
        Integer stock = seckillVoucher.getStock();
        if(stock < 1) {
            return Result.fail("没有库存");
        }
        //扣除库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)//加上乐观锁
                .update();
        if(!flag){
            return Result.fail("没有库存");
        }
        // 创建订单
        //1 订单id
        long orderId = redisIdWorker.nextId("order");
        //2 用户id
        Long userId = UserHolder.getUser().getId();
        VoucherOrder voucherOrder = VoucherOrder.builder().id(orderId).userId(userId)
                .voucherId(seckillVoucher.getVoucherId()).build();
        //保存订单
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
