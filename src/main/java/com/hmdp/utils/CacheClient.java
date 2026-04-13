package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheClient {
    private  final StringRedisTemplate redisTemplate;

    private final static ExecutorService CACHE_REBUILDER = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 对象 序列化为 json 也就是 key 设置key过期时间
     * @param key
     * @param time
     * @param timeUnit
     */
    public  void set(String key,Object value, Long time, TimeUnit timeUnit){
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,timeUnit);
    }

    /**
     * 对象 序列化为 json 也就是 key 设置逻辑过期
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithExpireTime(String key,Object value, Long time, TimeUnit timeUnit){
        //创建逻辑过期对象
        RedisData redisData = RedisData.builder()
                .expireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)))
                .data(value)
                .build();
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <T,ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbCallBack,Long time, TimeUnit timeUnit){ //缓存穿透
        //redis查询缓存
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        //存在 返回
        if(!StringUtils.isEmpty(json)){
            return JSONUtil.toBean(json,type);
        }
        // 如果是null  上边判断判定没有 但这不对
        if(json != null){
            return null;
        }
        //不存在 查数据库
        T entity = dbCallBack.apply(id);
        //不存在 返回404
        if(entity == null){
            redisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES); //写入空值
            return null;
        }
        //存在 写入redis
       set(key,entity,time,timeUnit);
        //返回
        return entity;
    }

    public <ID,T> T queryWithLogicalExpire(String keyPrefix, ID id,Class<T> type, Function<ID,T> dbCallBack,Long time, TimeUnit timeUnit){  //逻辑过期 解决缓存穿透
        //redis查询缓存
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        //不存在 返回
        if(StringUtils.isEmpty(json)){
            //查询数据库
            T entity = dbCallBack.apply(id);
            //不存在 返回404
            if(entity == null){
                return null;
            }
            //存在 写入redis
           saveShopRedis(id,10L,dbCallBack,type,keyPrefix);
            return entity;
        }
        //存在 判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // JSONObject data = (JSONObject) redisData.getData(); //直接转化为JSONObject
        T entity = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        //没有过期 返回shop
        if(expireTime.isAfter(LocalDateTime.now())){
            return entity;
        }
        //过期了 要缓存重建
        //1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if(flag){
            //成功  检查redis是否存在
            String shopJson2 = redisTemplate.opsForValue().get(key);
            RedisData redisData2 = JSONUtil.toBean(json, RedisData.class);
            if(!StringUtils.isEmpty(shopJson2) && !redisData2.getExpireTime().equals(expireTime)){ //不为空 表明存在
                    T entity2 = JSONUtil.toBean((JSONObject) redisData2.getData(),type);
                    return entity2;
            }
            // 创建独立线程 实现缓存重建
            CACHE_REBUILDER.submit( ()-> {try{
                this.saveShopRedis(id,20L,dbCallBack,type,keyPrefix); //实际是30min
            } catch (Exception e){
                throw  new RuntimeException();
            }finally {//释放锁
                unlock(lockKey);
            }
            });
        }
        //不成功  直接返回 旧的数据
        return entity;
    }

    public <ID,T> void saveShopRedis(ID id,Long expireSeconds,Function<ID,T> dbCallBack,Class<T> type,String keyPrefix){ //预热数据
        //查询
        T entity =dbCallBack.apply(id);
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //设置逻辑过期时间
        RedisData redisData = RedisData.builder()
                .data(entity)
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds)).build();
        //添加进入redis
        redisTemplate.opsForValue().set(keyPrefix + id,JSONUtil.toJsonStr(redisData));
    }

    /*
   获取锁
    */
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key){
        redisTemplate.delete(key);
    }


}
