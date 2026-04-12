package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();
        //获取user
        UserDTO userDTO = (UserDTO) session.getAttribute(SystemConstants.USER);
        //校验是否存在
        if(userDTO == null){
            response.setStatus(401);
            return false;
        }
        //存在 放进ThreadLocal
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
