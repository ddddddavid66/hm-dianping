package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
       if(RegexUtils.isPhoneInvalid(phone)){
           return Result.fail("手机号格式错误");
       }
       //生成验证码
       String code = RandomUtil.randomNumbers(6);
       //保存验证码
        session.setAttribute("code",code);
        session.setAttribute("phone",phone);
       //发送验证码
        log.debug("发送短信验证码成功,验证码" + code);
        return Result.ok("发送成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //检验 验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if(cacheCode == null || !code.equals(cacheCode.toString())){
            return Result.fail("验证码错误");
        }
        //以及是否和手机号一样
        Object phoneOrigin = session.getAttribute("phone");
        if(phoneOrigin == null ||  !phone.equals(phoneOrigin.toString())){
            return Result.fail("验证码错误");
        }
        //根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if(user == null){
            //不存在 注册
            user = createNewUser(phone);
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        //保存session  不需要返回登录凭证 session是有状态的
        session.setAttribute(SystemConstants.USER, userDTO);
        return Result.ok();
    }

    private User createNewUser(String phone) {
        User user = User.builder()
                .phone(phone)
                .nickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                .build();
        save(user);
        return user;
    }
}
