package com.tianji.promotion.utils;

import com.tianji.common.utils.BooleanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class RedisLock {
    private final StringRedisTemplate redisTemplate;
    private final String key;

    public boolean tryLock(Long leaseTime, TimeUnit unit){
        //获取线程信息
        String name = Thread.currentThread().getName();

        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, name, leaseTime, unit);

        return BooleanUtils.isTrue(success);
    }

    public void unlock(){
        redisTemplate.delete(key);
    }

}
