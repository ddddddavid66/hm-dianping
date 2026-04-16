package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private RedisIdWorker redisIdWorker;
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Resource
    private IShopService shopService;

    @Test
    void countTest(){
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long order = redisIdWorker.nextId("order");
                System.out.println(order);
            }
            countDownLatch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        long end = System.currentTimeMillis();
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        long d = end - start;
        System.out.println("start - end " + d);
    }

    @Test
    public void testShopTypeId(){
        // 查询店铺信息
        List<Shop> list = shopService.list();
        // 店铺分组
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(shop -> shop.getTypeId()));
        Set<Map.Entry<Long, List<Shop>>> entries = map.entrySet();
        for (Map.Entry<Long, List<Shop>> entry : entries) {
            Long typeId = entry.getKey();
            List<Shop> typeList = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> geoList = new ArrayList<>();
            for (Shop shop : typeList) {
                geoList.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY()))); //转化成List
            }
            //分批写入redis
            redisTemplate.opsForGeo().add(key,geoList);
        }
    }

    @Test
    void testUV(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                redisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        Long count = redisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(count);
    }

}
