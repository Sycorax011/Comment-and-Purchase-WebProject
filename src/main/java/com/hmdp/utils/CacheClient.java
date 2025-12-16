package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /*
    封装的工具类，用来查询指定的key，并反序列化为指定类型
    使用互斥锁进行缓存重建解决缓存击穿
    同时利用缓存空值的方式解决缓存穿透
     */
    public <R, ID> R queryWithPassThough(
            String keyPre, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit){

        String key = keyPre + id;

        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            //这里是value有字符串才返回，空串和没找到都会跳过去
            return JSONUtil.toBean(json, type);
        }
        //判断一下命中的是不是空值
        if(json != null){
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        boolean isLock = false;

        try {
            isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(100);  // 等待其他线程重建
                // 直接递归重试整个方法，大概率命中缓存
                return queryWithPassThough(keyPre, id, type, dbFallBack, time, unit);
            }

            //拿到锁了之后，说明你是第一个发起请求的人
            //双重检查：获取锁后再次查缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }
            //要是真的没找到，就去数据库里面找
            r = dbFallBack.apply(id);

            //要是还没找到
            if (r == null) {
                //写入空值，防止缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //写入redis
            this.set(key, r, time, unit);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            /*
            释放互斥锁
            如果当前线程没有成功获取锁（isLock == false），直接走了递归返回
            那finally仍然会执行 unlock(lockKey)，这会误删掉正在持有锁的线程的锁，导致多个线程同时进入重建逻辑，击穿数据库！
            必须只在成功获取锁时才释放。
             */
            if(isLock){
                unlock(lockKey);
            }
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 30, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
