package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author shadow_maples
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //1. 查询Redis
        String shopType = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if (StringUtils.hasText(shopType)) {
            //2. 若存在，直接返回
            List<ShopType> shopTypes = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //3. 若不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //4. 如果不存在，返回错误
        if (Objects.isNull(shopTypeList)) {
            return Result.fail("分类不存在");
        }
        //5. 数据库能查询到的话，先存入Redis
        if (!Objects.isNull(shopTypeList)) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        }
        //5. 返回数据
        return Result.ok(shopTypeList);
    }
}
