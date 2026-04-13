package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final long BEGIN_TIMESTAMP = 1776116784;
    private static final int COUNT_BITS = 32;
    private ExecutorService es = Executors.newFixedThreadPool(10);

    public long nextId(String keyPrefix){
        // 时间戳
        long timeStamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 自增
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd")); //日期为天
        Long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //拼接
        return timeStamp << COUNT_BITS | count;
    }



}
