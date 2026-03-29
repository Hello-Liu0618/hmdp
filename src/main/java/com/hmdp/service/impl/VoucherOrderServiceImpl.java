package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;//lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);//阻塞队列
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();//单线程池

    @PostConstruct//该注释意为，在类创建时执行此方法
    private void init() {
        //向单线程池提交任务处理阻塞队列
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //使用阻塞队列处理订单
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder order = orderTasks.take();
                    handleVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.error("处理订单异常: ", e);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder order) {
        long userId = order.getUserId();
        long voucherId = order.getVoucherId();

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if ( !isLock ) {
            //获取锁失败, 报错或重试
            //return Result.fail("不能重复购买秒杀优惠券!");
            log.error("不能重复重复购买秒杀优惠券!");//异步处理无需返回给前端, 理论上来说此处已经通过redis处理了并发问题, 此处依然设置锁结构是为了兜底
            return;
        }
        try {
            //获取代理对象(事务)
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //return proxy.createVoucherOrder(voucherId);
            proxy.createVoucherOrder(order);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

//    public VoucherOrderServiceImpl(ISeckillVoucherService iSeckillVoucherService, RedisIdWorker redisIdWorker, StringRedisTemplate stringRedisTemplate) {
//        this.iSeckillVoucherService = iSeckillVoucherService;
//        this.redisIdWorker = redisIdWorker;
//        this.stringRedisTemplate = stringRedisTemplate;
//    }


    private IVoucherOrderService proxy;
    /**
     * 秒杀优惠券下单--基于redis优化
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId  = UserHolder.getUser().getId();

        //执行lua脚本
        Long execute = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int executeResult = execute.intValue();
        //判断执行结果是否为0
        //不为0， 没有购买资格
        switch (executeResult) {
            case 1: {
                log.info("秒杀优惠券库存不足, voucherId={}", voucherId);
                return Result.fail("秒杀优惠券库存不足!");
            }
            case 2: {
                log.info("用户重复购买秒杀优惠券, voucherId={}, userId={}", voucherId, userId);
                return Result.fail("不能重复购买秒杀优惠券!");
            }
            default: {
                break;
            }
        }
        //为0，有购买资格，把订单保存到阻塞队列

        //新建订单
        VoucherOrder  voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        //将订单放入阻塞队列
        orderTasks.add(voucherOrder);
        //创建代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        log.info("秒杀优惠券订单已经添加到阻塞队列, orderId={}", orderId);
        //返回订单Id
        log.info("秒杀优惠券下单完成, orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
        return Result.ok(orderId);
    }

    /**
     * 秒杀优惠券下单--初始版本
     *
     * @param order
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询得到优惠券
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//
//        //判断优惠券是否存在
//        if ( seckillVoucher == null ) {
//            log.info("秒杀优惠券不存在, voucherId={}", voucherId);
//            return Result.fail("秒杀优惠券不存在!");
//        }
//        //判断秒杀是否开始
//        if ( seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()) ) {
//            return Result.fail("秒杀尚未开始!");
//        }
//        if ( seckillVoucher.getEndTime().isBefore(LocalDateTime.now()) ) {
//            return Result.fail("秒杀已经结束!");
//        }
//
//        //判断秒杀优惠券的库存情况
//        if ( seckillVoucher.getStock() < 1 ) {
//            return Result.fail("秒杀优惠券库存不足!");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //尝试获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if ( !isLock ) {
//            //获取锁失败, 报错或重试
//            return Result.fail("不能重复购买秒杀优惠券!");
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        //一人一单
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        //Long orderId = order.getId();
//        Long userId = UserHolder.getUser().getId();

        //对于同一个用于加悲观锁保证不会再并发情况下出现一人多单


            int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
            if (count > 0) {
                //用户已经购买过此优惠券
                log.error("用户重复购买秒杀优惠券, userId={}, voucherId={}", userId, voucherId);
            }

            //减扣库存
            //乐观锁解决超卖问题
            boolean success = iSeckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣除失败
                log.error("秒杀优惠券库存不足, voucherId = {}", voucherId);
            }
            save(order);
            log.info("秒杀优惠券购买成功, voucherId = {}, userId={}", voucherId, userId);
    }
}
