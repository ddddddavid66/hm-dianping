package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Resource
    private IShopTypeService typeService;


    @Override
    public Result queryShopList() {
        //先去查询redis 看看有没有
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String typeJson = redisTemplate.opsForValue().get(key);
        if(!StringUtils.isEmpty(typeJson)){
            //有 直接返回
            List<ShopType> typeList = JSONUtil.toList(typeJson,ShopType.class);
            return Result.ok(typeList);
        }
        //没有 查询数据库
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        //数据库没有 返回空
        if(typeList.isEmpty()){
            redisTemplate.opsForValue().set(key,null);
            return Result.fail("没有商户类型");
        }
        //有 返回 加 写入redis
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
