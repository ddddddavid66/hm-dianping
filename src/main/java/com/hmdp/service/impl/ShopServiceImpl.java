package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate redisTemplate;

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

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        //1 更新数据库
        updateById(shop);
        //2 删除缓存
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺不存在");
        }
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    public Shop queryWithPassThrough(Long id){ //缓存穿透
        //redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //存在 返回
        if(!StringUtils.isEmpty(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        // 如果是null  上边判断判定没有 但这不对
        if(shopJson != null){
            return null;
        }
        //不存在 查数据库
        Shop shop = getById(id);
        //不存在 返回404
        if(shop == null){
            redisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES); //写入空值
            return null;
        }
        //存在 写入redis
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    public Shop queryWithMutex(Long id){
        String lockKey = null;
        Shop shop = null;
        try {
            //redis查询缓存
            String key = RedisConstants.CACHE_SHOP_KEY + id;
            String shopJson = redisTemplate.opsForValue().get(key);
            //存在 返回
            if(!StringUtils.isEmpty(shopJson)){
                return JSONUtil.toBean(shopJson,Shop.class);
            }
            // 如果是null  上边判断判定没有 但这不对
            if(shopJson != null){
                return null;
            }
            //不存在 实现缓存重建
            //获取互斥锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean flag = tryLock(lockKey);
            if(!flag){
                //失败 休眠 重试
                Thread.sleep(50);
                return queryWithMutex(id); // 递归
            }
            // 成功   再次 检查缓存是否存在
            String shopJson2 = redisTemplate.opsForValue().get(key);
            //存在 返回
            if(!StringUtils.isEmpty(shopJson2)){
                return JSONUtil.toBean(shopJson2,Shop.class);
            }
            // 如果是null呢
            if(shopJson2 != null){
                return null;
            }
            // 不存在 查询数据库
            shop = getById(id);
            //不存在 返回404
            if(shop == null){
                redisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES); //写入空值
                return null;
            }
            //存在 写入redis
            redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(lockKey);
        }
        //返回
        return shop;
    }

}
