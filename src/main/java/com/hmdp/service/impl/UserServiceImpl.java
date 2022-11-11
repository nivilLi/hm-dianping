package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.ClusterOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.nio.file.CopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功: " + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (code == null || !loginForm.getCode().equals(code)) {
            return Result.fail("验证码错误");
        }
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getPhone, loginForm.getPhone());
        User user = this.getOne(userLambdaQueryWrapper);
        if (user == null) {
            user = this.createUserWithPhone(loginForm.getPhone());
            this.save(user);
        }
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        Map<String, Object> beanToMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((filed, value) -> {
            return value.toString();
        }));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, beanToMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return Result.ok(token);
    }

    public User createUserWithPhone(String Phone) {
        User user = new User();
        user.setNickName("user" + RandomUtil.randomString(10));
        user.setPhone(Phone);
        return user;
    }

    @Override
    public Result sign() {
        Long id = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int day = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(RedisConstants.USER_SIGN_KEY + id + date, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result singCount() {
        Long id = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int day = now.getDayOfMonth();
        List<Long> longs = stringRedisTemplate.opsForValue().bitField(RedisConstants.USER_SIGN_KEY + id + date, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        if (longs == null || longs.isEmpty()) {
            return Result.ok(0);
        }
        Long result = longs.get(0);
        if (result == null || result == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if (result % 2 == 0) {
                break;
            } else {
                count++;
            }
            result >>>= 1;
        }
        return Result.ok(0);
    }

}
