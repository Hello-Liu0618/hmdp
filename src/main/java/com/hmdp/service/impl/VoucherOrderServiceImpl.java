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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private final ISeckillVoucherService iSeckillVoucherService;
    private final RedisIdWorker redisIdWorker;

    public VoucherOrderServiceImpl(ISeckillVoucherService iSeckillVoucherService, RedisIdWorker redisIdWorker) {
        this.iSeckillVoucherService = iSeckillVoucherService;
        this.redisIdWorker = redisIdWorker;
    }

    public Result seckillVoucher(Long voucherId) {
        //查询得到优惠券
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);

        //判断优惠券是否存在
        if ( seckillVoucher == null ) {
            log.info("秒杀优惠券不存在, voucherId={}", voucherId);
            return Result.fail("秒杀优惠券不存在!");
        }
        //判断秒杀是否开始
        if ( seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()) ) {
            return Result.fail("秒杀尚未开始!");
        }
        if ( seckillVoucher.getEndTime().isBefore(LocalDateTime.now()) ) {
            return Result.fail("秒杀已经结束!");
        }

        //判断秒杀优惠券的库存情况
        if ( seckillVoucher.getStock() < 1 ) {
            return Result.fail("秒杀优惠券库存不足!");
        }
        //一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if ( count > 0 ) {
            //用户已经购买过此优惠券
            log.info("用户重复购买秒杀优惠券, userId={}, voucherId={}", userId, voucherId);
            return Result.fail("不能重复购买秒杀优惠券!");
        }

        //减扣库存
        //乐观锁解决超卖问题
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if ( !success ) {
            //扣除失败
            log.info("秒杀优惠券库存不足, voucherId = {}", voucherId);
            return Result.fail("秒杀优惠券库存不足!");
        }

        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //设置订单号
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //设置用户id
        voucherOrder.setUserId(userId);
        //设置秒杀优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        log.info("秒杀优惠券购买成功, voucherId = {}, userId={}", voucherId, userId);
        return Result.ok(orderId);
    }
}
