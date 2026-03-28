package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

//    public VoucherOrderServiceImpl(ISeckillVoucherService iSeckillVoucherService, RedisIdWorker redisIdWorker, StringRedisTemplate stringRedisTemplate) {
//        this.iSeckillVoucherService = iSeckillVoucherService;
//        this.redisIdWorker = redisIdWorker;
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

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
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if ( !isLock ) {
            //获取锁失败, 报错或重试
            return Result.fail("不能重复购买秒杀优惠券!");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();

        //对于同一个用于加悲观锁保证不会再并发情况下出现一人多单


            int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
            if (count > 0) {
                //用户已经购买过此优惠券
                log.info("用户重复购买秒杀优惠券, userId={}, voucherId={}", userId, voucherId);
                return Result.fail("不能重复购买秒杀优惠券!");
            }

            //减扣库存
            //乐观锁解决超卖问题
            boolean success = iSeckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
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
