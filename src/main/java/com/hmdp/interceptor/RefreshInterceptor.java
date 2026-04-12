package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshInterceptor implements HandlerInterceptor {

    private StringRedisTemplate redisTemplate;

    public RefreshInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String tokenkey = request.getHeader("authorization");
        if(StringUtils.isEmpty(tokenkey)){
            return true;
        }
        String token = RedisConstants.LOGIN_USER_KEY +  tokenkey ;
        //获取 redis 获取 user
        Map<Object, Object> map = redisTemplate.opsForHash().entries(token);
        //校验是否存在
        if(map.isEmpty()){
            return true;
        }
        //转换 User
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(),false);
        //存在 放进ThreadLocal
        UserHolder.saveUser(userDTO);
        //设置token有效期
        redisTemplate.expire(token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
