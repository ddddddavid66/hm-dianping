package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MVCConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns("/user/login","/user/code","/blog/hot","/shop/**","/shop-type/**","/voucher/**");
        //排除登录 热点 优惠券 店铺类型
        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
