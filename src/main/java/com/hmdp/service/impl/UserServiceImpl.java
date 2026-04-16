package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

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
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
       if(RegexUtils.isPhoneInvalid(phone)){
           return Result.fail("手机号格式错误");
       }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码  保存到Redis
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
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
        String cacheCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if( !code.equals(cacheCode)){
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
        //保存Redis 生成token
        String token =  UUID.randomUUID().toString();
        String tokenKey = LOGIN_USER_KEY + token;
        // User 转换成Map 存储进入reids
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName,fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString())); //把long转换
        redisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期  用户一直登录
        redisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        // token还需要  写入浏览器 返回前端即可
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDate now = LocalDate.now();
        String keyPrefix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key =  USER_SIGN_KEY  + userId+ keyPrefix;
        //获取 今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis
        redisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDate now = LocalDate.now();
        String keyPrefix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key =  USER_SIGN_KEY  + userId+ keyPrefix;
        //获取 今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月的签到结果 得到10进制
        List<Long> result = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        //让数字与1 做 与运算
        // 得到的数 判断是否是0  0代表未签到
        // 如果是1 代表签到了 所以 右移一位  直到遇到0
        while(true){
            if((num & 1) == 0){
                break;
            }else{
                count++;
                num >>>= 1; //右移
            }
        }
        return Result.ok(count);
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
