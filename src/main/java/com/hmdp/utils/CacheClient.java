package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
    利用缓存空值的方式解决缓存穿透问题
    其实就是把实现类里面解决缓存穿透的方法封装了一下
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

        //要是真的没找到，就去数据库里面找
        R r = dbFallBack.apply(id);

        //要是还没找到
        if(r == null){
            //写入空值，防止缓存穿透
            stringRedisTemplate.opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //写入redis
        this.set(key, r, time, unit);
        return r;
    }
}
