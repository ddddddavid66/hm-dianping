package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Resource
    private IUserService userService;


    @Override
    public Result follow(Long id, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW + userId;
        if(isFollow){
            //  关注 新增数据
            Follow follow = Follow.builder().userId(userId).followUserId(id).build();
            boolean flag = save(follow);
            //进入redis
            if(flag){
                redisTemplate.opsForSet().add(key,id.toString()); //followUserId
            }
        }else{
            // 取关
            boolean flag = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if(flag){
                redisTemplate.opsForSet().remove(key,id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW + userId;
        String followKey =  FOLLOW  + id;
        //redis查询交集
        Set<String> intersect = redisTemplate.opsForSet().intersect(key, followKey);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok();
        }
        //解析id集合
        List<Long> userIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(userIds).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOList);
    }


}
