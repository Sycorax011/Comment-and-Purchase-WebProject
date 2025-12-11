package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
        //防止缓存穿透
        Shop shop = cacheClient
               .queryWithPassThough
                        (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁防止缓存击穿
        //shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //这里是value有字符串才返回，空串和没找到都会跳过去
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断一下命中的是不是空值
        if(shopJson != null){
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //拿到锁了之后，说明你是第一个发起请求的人，就去数据库里面找
            shop = getById(id);
            //Thread.sleep(200);
            //要是还没找到
            if (shop == null) {
                //写入空值，防止缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    //获取锁和释放锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
