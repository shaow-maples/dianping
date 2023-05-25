package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 设置TTL过期时间，将对象序列化为json并存储在string类型的key中
     */
    public void set(String key, Object object, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object), ttl, unit);
    }

    /**
     * 设置逻辑过期时间，将对象序列化为json并存储在string类型的key中，用于解决缓存击穿问题
     */
    public void setWithLogicalExpire(String key, Object object, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(object);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透问题
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        //1. 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StringUtils.hasText(json)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            //返回错误信息
            return null;
        }
        //4. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        if (Objects.isNull(r)) {
            //5. 数据库中不存在，返回错误
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 数据库中存在，写入redis
        this.set(key, r, ttl, unit);
        //7. 返回数据
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用逻辑删除方案解决缓存击穿
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        //1. 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (!StringUtils.hasText(json)) {
            //3. 不存在，直接返回
            return null;
        }
        //4. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return r;
        }
        //5.2 过期，需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock) {
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, ttl, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }
        //6.4返回过期的店铺数据
        return r;
    }

    /**
     * 使用互斥锁方案解决缓存击穿
     */
    public <R,ID> R queryWithMutex(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        //1. 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StringUtils.hasText(json)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            //返回错误信息
            return null;
        }
        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, ttl, unit);
            }

            //不存在，根据id查询数据库
            r = dbFallback.apply(id);
            if (Objects.isNull(r)) {
                //5. 数据库中不存在，返回错误
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 数据库中存在，写入redis
            this.set(key, r, ttl, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unlock(lockKey);
        }
        //8. 返回数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
