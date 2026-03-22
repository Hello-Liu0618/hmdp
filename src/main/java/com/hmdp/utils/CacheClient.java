package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 设置redis缓存键值以及过期时间
     * @param key
     * @param value
     * @param expireTime
     * @param timeUnit
     */
    public void set(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, jsonStr, expireTime, timeUnit);
    }

    /**
     * 设置redis键值并采用逻辑过期
     * @param key
     * @param value
     * @param expireTime
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     *解决了缓存穿透的Redis读取
     * @param id
     * @param type
     * @return
     * @param <R>
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit
    ) {//解决了缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //缓存中有店铺数据
        if ( StrUtil.isNotBlank(json) ) {//判断是否为null 或者 ""
            log.info("缓存命中, key: {}",key);
            //读取缓存
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            //不是null则为"". 为防止缓存穿透设置
            log.info("缓存穿透命中, key: {}",key);
            return null;
        }
        //若缓存中没有, 则从数据库中读取
        log.info("缓存缺失, key: {}", key);
        R r = dbFallback.apply(id);

        if (r == null) {
            //设置缓存空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            log.info("缓存穿透, key: {}",key);
            //返回错误信息
            return null;
        }
        //写入缓存
        this.set(key, r, time, unit);
        log.info("缓存写入, key: {}", key);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit
    ) {//解决了缓存穿透
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //缓存中无店铺数据
        if ( StrUtil.isBlank(json) ) {
            log.info("缓存缺失, key: {}",key);
            return null;
        }
        //若缓存命中
        log.info("缓存命中, key: {}", key);
        //获取商铺信息以及过期时间信息
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);

        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = BeanUtil.toBean(jsonObject, type);

        LocalDateTime expireTime = redisData.getExpireTime();

        //判断缓存是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {//缓存尚未过期
            log.info("缓存逻辑不过期, key: {}",key);
            return r;
        }
        //缓存已经过期
        log.info("缓存逻辑过期, key: {}", key);
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if ( isLock ) {//获取锁成功, 则开启独立线程进行缓存内容更新
            //开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = dbFallback.apply(id);
                    //模拟重建缓存时间
                    //Thread.sleep(300);
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                    //this.setWithLogicalExpire(key, r1, 10L, TimeUnit.SECONDS);
                    log.info("重建缓存, key: {}",key);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //如果获取锁失败, 说明已经有其他线程在对缓存数据进行更行了, 直接返回旧数据

        return r;
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
}
