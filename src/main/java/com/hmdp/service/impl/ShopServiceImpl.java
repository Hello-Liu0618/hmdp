package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 使用缓存查询店铺
     * @param id
     * @return
     */
    public Result  queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //使用互斥锁解决缓存击穿(增量自queryWithPassThrough(), 故也解决了缓存穿透)
        //Shop shop = queryWithMutex(id);

        //使用逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        if (  null == shop) {
            log.info("店铺访问失败!");
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {//解决了缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //缓存中无店铺数据
        if ( StrUtil.isBlank(shopJson) ) {
            log.info("商铺缓存缺失, key: {}",key);
            return null;
        }
        //若缓存命中
        log.info("商铺缓存命中, key: {}", key);
        //获取商铺信息以及过期时间信息
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = BeanUtil.toBean(jsonObject, Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();

        //判断缓存是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {//缓存尚未过期
            log.info("缓存逻辑不过期, key: {}",key);
            return shop;
        }
        //缓存已经过期
        log.info("缓存逻辑过期, key: {}", key);
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if ( isLock ) {//获取锁成功, 则开启独立线程进行缓存内容更新
            //开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //如果获取锁失败, 说明已经有其他线程在对缓存数据进行更行了, 直接返回旧数据

        return shop;
    }


    /**
     * 利用互斥锁防止缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //缓存中有店铺数据
        if ( StrUtil.isNotBlank(shopJson) ) {//判断是否为null 或者 ""
            log.info("商铺缓存命中, key: {}",key);
            //读取缓存
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            //不是null则为"". 为防止缓存穿透设置
            log.info("商铺缓存穿透命中, key: {}",key);
            return null;
        }
        log.info("商铺缓存缺失, key: {}", key);
        //若缓存中没有, 则从数据库中读取

        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判读是否获取成功
            //失败则休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功则查询数据库

            shop = getById(id);
            //log.info("一个巨大的标志!=========================================================");

            //模拟重建缓存的耗时
            //Thread.sleep(500);

            //不存在则返回错误
            if (shop == null) {
                //设置缓存控制防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                log.info("商铺缓存穿透, key: {}",key);
                //返回错误信息
                return null;
            }
            //写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存过期时间为30分钟
            log.info("商铺缓存写入, key: {}", key);
            //log.info("一个巨大的标识!!!!================================================================");
        } catch (InterruptedException e) {
            log.info("查询店铺获取互斥锁异常: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }

        //返回
        return shop;
    }

    /**
     * 防止缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {//解决了缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //缓存中有店铺数据
        if ( StrUtil.isNotBlank(shopJson) ) {//判断是否为null 或者 ""
            log.info("商铺缓存命中, key: {}",key);
            //读取缓存
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            //不是null则为"". 为防止缓存穿透设置
            log.info("商铺缓存穿透命中, key: {}",key);
            return null;
        }
        //若缓存中没有, 则从数据库中读取
        log.info("商铺缓存缺失, key: {}", key);
        Shop shop = getById(id);

        if (shop == null) {
            //设置缓存空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            log.info("商铺缓存穿透, key: {}",key);
            //返回错误信息
            return null;
        }
        //写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存过期时间为30分钟
        log.info("商铺缓存写入, key: {}", key);
        return shop;
    }

    /**
     * 使用redis作为互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean lockFlag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lockFlag);
    }

    /**
     * 使用redis作为互斥锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    /**
     * 重建逻辑缓存
     * @param id
     * @param expireSeconds
     * @throws InterruptedException
     */
    public void saveShop2Redis  (Long id, Long expireSeconds) throws InterruptedException {
        log.info("重建逻辑缓存");
        //更具店铺id查询数据库获取店铺对象
        Shop shop = getById(id);

        //模拟重建缓存花费时间
        //Thread.sleep(200);

        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新店铺数据并清除缓存
     * @param shop
     * @return
     */
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            //店铺id为空
            return Result.fail("店铺id不能为空!");
        }
        //先修改数据库
        updateById(shop);
        //再清除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
