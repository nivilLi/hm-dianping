package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
@Autowired
private StringRedisTemplate stringRedisTemplate;
@Resource
private IUserService userService;
    @Override
    public Result follow(Long followUserId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow){
            LambdaQueryWrapper<Follow> followQueryWrapper = new LambdaQueryWrapper<>();
            followQueryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
            boolean success = this.removeById(followQueryWrapper);
            if (success)
                stringRedisTemplate.opsForSet().remove("follows:" + userId, followUserId.toString());
            return Result.ok();
        }
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followUserId);
        follow.setCreateTime(LocalDateTime.now());
        boolean success = this.save(follow);
        if (success)
            stringRedisTemplate.opsForSet().add("follows:" + userId, followUserId.toString());
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long userId) {
        Long currentUserId = UserHolder.getUser().getId();
        Set<String> followIds = stringRedisTemplate.opsForSet().intersect("follows:" + userId, "follows:" + currentUserId);
        List<Long> ids = followIds.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> {
            return BeanUtil.copyProperties(user, UserDTO.class);
        }).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
