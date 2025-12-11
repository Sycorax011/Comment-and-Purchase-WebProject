package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PRE = "lock";
    private static final String ID_PRE = UUID.randomUUID().toString() + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name; //userId
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程的标识
        String threadId = ID_PRE + Thread.currentThread().getId();
        //获取锁
        boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PRE + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        /*
        //获取线程中的标识
        String threadId = ID_PRE + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PRE + name);//获取的value

        if(threadId.equals(id)){
            stringRedisTemplate.delete(KEY_PRE + name);
        }
        */
        /*
        Spring事务管理的是数据库事务（通常是关系型数据库如MySQL），它无法管理对Redis的操作
        Redis有自己的事务（MULTI/EXEC），但它和Spring的@Transactional是两套完全不同的体系
        所以，你不能指望@Transactional来保证Redis操作的原子性

        锁本身（tryLock）就是为了保障业务代码块的原子性
        但 unlock 这个操作本身是锁生命周期的外部管理操作，它自己也需要原子性，而它自己不能为自己提供保障

        所以，我们需要一个凌驾于普通Redis命令之上的机制，即Lua脚本
        保证get -> compare -> delete 这三个逻辑步骤合并成一个不可分割的原子操作
         */
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PRE + name),
                ID_PRE + Thread.currentThread().getId()
        );
    }
}
