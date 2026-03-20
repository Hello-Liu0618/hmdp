package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
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
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //缓存中有店铺数据
        if ( StrUtil.isNotBlank(shopJson) ) {
            log.info("商铺缓存命中, key: {}",key);
            //读取缓存
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //若缓存中没有, 则从数据库中读取
        log.info("商铺缓存缺失, key: {}", key);
        Shop shop = getById(id);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        //写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);//设置缓存过期时间为30分钟
        log.info("商铺缓存写入, key: {}", key);
        return Result.ok(shop);
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
