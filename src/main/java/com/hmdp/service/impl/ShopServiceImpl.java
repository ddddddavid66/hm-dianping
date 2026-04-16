package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jodd.util.StringUtil;
import org.apache.tomcat.jni.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Autowired
    private CacheClient cacheClient;
    private final static ExecutorService CACHE_REBUILDER = Executors.newFixedThreadPool(10);
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


        //Shop shop = queryWithMutex(id);
        //Shop shop = queryWithLogicalExpire(id);

        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //缓存 击穿
        Shop shop =  cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
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

    @Override
    public Result qieryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要坐标
        if(x == null || y == null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页查询
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis  按照距离排序   圆心 半径 是否返回距离
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y)
                , new Distance(5000),//默认是m
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));//.limit 永远从1 开始
         if(results == null ){
             return Result.ok(Collections.emptyList());
         }
         List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
         List<Long> shopIds = new ArrayList<>(content.size());
        Map<String,Distance> distanceMap = new TreeMap<>();
        // 解析id  需要手动截取 因为from 从1 开始
         content.stream().skip(from).forEach(result -> {
             String shopId = result.getContent().getName();
             shopIds.add(Long.valueOf(shopId));
             Distance distance = result.getDistance();
             distanceMap.put(shopId,distance);
         });
         if(shopIds == null || shopIds.isEmpty()){
             return Result.ok(Collections.emptyList());
         }
        //根据id查询 shop
        String str = StringUtil.join(shopIds, ",");
        List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD(id," + str + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 根据类型分页查
        return Result.ok(shops);
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

    public Shop queryWithMutex(Long id){  //互斥锁 解决缓存穿透
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

    public Shop queryWithLogicalExpire(Long id){  //逻辑过期 解决缓存穿透
        //redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //不存在 返回
        if(StringUtils.isEmpty(shopJson)){
            //查询数据库
            Shop shop = getById(id);
            //不存在 返回404
            if(shop == null){
                return null;
            }
            //存在 写入redis
            saveShopRedis(id,10L);
            return shop;
        }
        //存在 判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // JSONObject data = (JSONObject) redisData.getData(); //直接转化为JSONObject
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        //没有过期 返回shop
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //过期了 要缓存重建
        //1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if(flag){
            //成功  检查redis是否存在
            String shopJson2 = redisTemplate.opsForValue().get(key);
            if(!StringUtils.isEmpty(shopJson2)){ //不为空 表明存在
                RedisData redisData2 = JSONUtil.toBean(shopJson, RedisData.class);
                Shop shop2 = JSONUtil.toBean((JSONObject) redisData2.getData(),Shop.class);
                return shop2;
            }
            // 创建独立线程 实现缓存重建
            CACHE_REBUILDER.submit( ()-> {try{
                this.saveShopRedis(id,20L); //实际是30min
            } catch (Exception e){
                throw  new RuntimeException();
            }finally {//释放锁
                unlock(lockKey);
            }
            });
        }
        //不成功  直接返回 旧的数据
        return shop;
    }

    public void saveShopRedis(Long id,Long expireSeconds){ //预热数据
        //查询
        Shop shop = getById(id);
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //设置逻辑过期时间
        RedisData redisData = RedisData.builder()
                .data(shop)
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds)).build();
        //添加进入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }


}
