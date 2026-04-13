package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class RedisTest {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    public  void testRedis(){
    shopService.saveShopRedis(1L,100L);
    }
}
