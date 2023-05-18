package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author shadow_maples
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //在解决缓存穿透的基础上，使用互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //在解决缓存穿透的基础上，使用逻辑删除解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (Objects.isNull(shop)) {
            return Result.fail("店铺不存在！");
        }
        //返回
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑删除方案的缓存击穿
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询店铺缓存,之前使用了hash存储，这里尝试一下用string存储对象数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (!StringUtils.hasText(shopJson)) {
            //3. 不存在，直接返回
            return null;
        }
        //4. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return shop;
        }
        //5.2 过期，需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock) {
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }
        //6.4返回过期的店铺数据
        return shop;
    }

    /**
     * 缓存穿透and互斥锁方案的缓存击穿
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询店铺缓存,之前使用了hash存储，这里尝试一下用string存储对象数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StringUtils.hasText(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //不存在，根据id查询数据库
            shop = getById(id);
            if (Objects.isNull(shop)) {
                //5. 数据库中不存在，返回错误
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 数据库中存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unlock(lockKey);
        }
        //8. 返回数据
        return shop;
    }

    /**
     * 缓存穿透
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询店铺缓存,之前使用了hash存储，这里尝试一下用string存储对象数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StringUtils.hasText(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        if (Objects.isNull(shop)) {
            //5. 数据库中不存在，返回错误
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 数据库中存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7. 返回数据
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1. 查询店铺数据
        Shop shop = getById(id);
        //2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
