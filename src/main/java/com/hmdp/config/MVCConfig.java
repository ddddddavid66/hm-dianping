package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MVCConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //排除登录 热点 优惠券 店铺类型
        registry.addInterceptor(new LoginInterceptor(redisTemplate))
                .excludePathPatterns("/user/login","/user/code","/blog/hot","/shop/**","/shop-type/**","/voucher/**")
                .order(1);
        //刷新拦截器
        registry.addInterceptor(new RefreshInterceptor(redisTemplate))
                .addPathPatterns("/**")
                .order(0);
        WebMvcConfigurer.super.addInterceptors(registry);
    }

}
