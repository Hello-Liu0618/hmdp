package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Result queryTypeList() {
        List<String> typeListJsonString = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY, 0, -1);
        List<ShopType> typeList = new ArrayList<>();
        if (typeListJsonString == null || typeListJsonString.isEmpty()) {
            //缓存缺失
            log.info("商铺类型缓存缺失!");
            //访问数据库
            typeList = query().orderByAsc("sort").list();
            if ( typeList == null || typeList.isEmpty() ) {
                return Result.fail("商铺类型数据为空!");
            }
            //缓存写入
            typeList.forEach(item -> {
                stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOPTYPE_KEY, JSONUtil.toJsonStr(item));
            });
            log.info("商铺类型缓存写入.");
            return Result.ok(typeList);
        }
        //缓存命中
        log.info("商铺类型缓存命中.");
        for (String item : typeListJsonString) {
            //转换为对象列表
            ShopType shopType = JSONUtil.toBean(JSONUtil.toJsonStr(item), ShopType.class);
            typeList.add(shopType);
        }
        return Result.ok(typeList);
    }

}
