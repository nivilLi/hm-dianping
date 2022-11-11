package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Scroll;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.omg.PortableInterceptor.Interceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FollowMapper followMapper;

    @Override
    public Result queryById(long id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    public void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        Long currentUserId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), currentUserId.toString());
        blog.setIsLike(BooleanUtil.isTrue(score != null));
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null )
            return Result.ok();
        Long userId = userDTO.getId();
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        if (score != null){
            boolean isSuccess = blogService.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess)
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        }else{
            boolean isSuccess = blogService.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess)
            stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikeUsers(Long blogId) {
        Set<String> stringUserIds = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + blogId, 0, 4);
        if (stringUserIds == null){
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = stringUserIds.stream().map(Long::valueOf).collect(Collectors.toList());
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        String join = StrUtil.join(",", userIds);
        List<UserDTO> userDTOS = userService.query().in("user_id", userIds).last("order by filed(id, " + join +  ")").list().stream().map(user ->
             BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = blogService.save(blog);
        // 返回id
        if (success){
            LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
            followLambdaQueryWrapper.select(Follow::getUserId).eq(Follow::getFollowUserId, user.getId());
            List<Object> fans = followMapper.selectObjs(followLambdaQueryWrapper);
            for (Object fan : fans) {
                stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + fan.toString(), blog.getId().toString(), System.currentTimeMillis());
            }
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result feed(long maxStamp, int offset) {
        Long id = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + id, 0, maxStamp, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty())
            return Result.ok();
        ArrayList<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minStamp = 0;
        int count = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String followId = typedTuple.getValue();
            blogIds.add(Long.valueOf(followId));
            long temp = typedTuple.getScore().longValue();
            if(temp == minStamp) {
                count++;
                continue;
            }
            minStamp = temp;
            count = 1;
        }
        String join = StrUtil.join(",", blogIds);
        List<Blog> blogs = this.query().in("id", blogIds).last("order by filed(id, " + join + ")").list();
        Scroll scroll = new Scroll();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
        }
        scroll.setList(blogs);
        scroll.setMinStamp(minStamp);
        scroll.setOffset(count);
        return Result.ok(scroll);
    }
}
