package com.hmdp.config;

import com.hmdp.utils.LoginIntercepter;
import com.hmdp.utils.ReflushTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MVCConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ReflushTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**");
        registry.addInterceptor(new LoginIntercepter(stringRedisTemplate)).excludePathPatterns("/user/code", "/user/login",
                "/blog/hot", "/shop/**", "/shop-type/**", "/voucher/**", "/upload/**").order(1);

    }
}
