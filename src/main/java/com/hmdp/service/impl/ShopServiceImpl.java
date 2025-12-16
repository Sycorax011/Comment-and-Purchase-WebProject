package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private Cache<String, Object> localCache; // Caffeine
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    @Transactional
    /*
    旁路缓存模式（Cache-Aside Pattern）
     */
    //-主动的-缓存更新，为了节省资源，数据库变化的时候先删除缓存
    //当需要查询的时候再重新写入
    public Result update(Shop shop) {
        /*
        先更新数据库再做缓存
        因为写入和读取的效率高用时短，在这期间不易被其他线程抢占导致并行问题
         */
        if(shop.getId() == null){
            return Result.fail("店铺ID不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 查一级缓存 (Caffeine)
        Shop shop = (Shop) localCache.getIfPresent(key);
        if (shop != null) {
            return Result.ok(shop);
        }

        //防止缓存穿透和缓存击穿
        shop = cacheClient
               .queryWithPassThough
                        (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在！");
        }else{
            //回填到 Caffeine（空值不要回填）
            localCache.put(key, shop);
        }
        return Result.ok(shop);
    }

}
