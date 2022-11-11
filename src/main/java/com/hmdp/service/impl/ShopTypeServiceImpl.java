package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String shopTypeListJson = stringRedisTemplate.opsForValue().get("cache:shopType");
        if (StringUtils.isNotBlank(shopTypeListJson)){
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();
        if (typeList == null){
            return Result.fail("无店铺信息");
        }
        shopTypeListJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set("cache:shopType", shopTypeListJson);
        return Result.ok(typeList);
    }
}
