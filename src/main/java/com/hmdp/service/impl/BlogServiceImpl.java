package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate redisTemplate;


    @Override
    public Result queryByBlogId(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        // 查询用户id
        queryByUserId(blog);
        // 查看是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryByUserId(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取登录信息
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY+ id;
        //判断是否点赞
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            // 没有点赞  修改点赞数量
            boolean flag2 = blogService.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            if(flag2){
                redisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            return Result.ok("点赞成功");
        }
        //点过了 取消点赞 并且修改isLike
        boolean flag3 = blogService.update()
                .setSql("liked = liked - 1").eq("id", id).update();
        if(flag3){
            redisTemplate.opsForZSet().remove(key,userId.toString()); // 改redis删除用户
        }
        return Result.ok("成功取消点赞");
    }

    @Override
    public Result queryByBlogLikes(Long id) {
        // 查询前5名
        String key = BLOG_LIKED_KEY + id;
        Set<String> usersMap = redisTemplate.opsForZSet().range(key, 0, 4);
        if(usersMap == null || usersMap.isEmpty()){
            return Result.fail("失败");
        }
        // 解析用户id  ORDER BY FIELD(id,5,1)
        List<Long> uesrIds = usersMap.stream().map(Long::valueOf).collect(Collectors.toList());
        String str = StringUtil.join(uesrIds, ","); // 5 , 1
        List<UserDTO> userList = userService.query().in("id",uesrIds)
                .last("ORDER BY FIELD(id, +" + str  +  " )").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userList);
    }

    public void queryByUserId(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    public void isBlogLiked(Blog blog){
        Long userId = blog.getUserId();
        String key = "blog:liked:" + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            blog.setIsLike(false);
        }else{
            blog.setIsLike(true);
        }
    }
}
