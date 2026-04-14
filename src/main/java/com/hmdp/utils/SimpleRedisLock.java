package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;

    public SimpleRedisLock(String name,StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    private static final String KEY_PREFIX  = "lock";
    private static final String ID_PREFIX  = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private StringRedisTemplate redisTemplate;

    static { //静态代码块
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程id
        long threadId = Thread.currentThread().getId();
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, ID_PREFIX + threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unLock() {
        String threadId = ID_PREFIX  + Thread.currentThread().getId();
        String lockId = redisTemplate.opsForValue().get(KEY_PREFIX + name);
        //Lua 脚本实现
        redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockId),Collections.singletonList(threadId));
        //释放锁 失败说明超时 或者别人进入了线程 所以什么也不做
    }
}
