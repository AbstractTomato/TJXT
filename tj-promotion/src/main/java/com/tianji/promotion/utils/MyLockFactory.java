package com.tianji.promotion.utils;

import com.tianji.common.exceptions.BizIllegalException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.naming.Name;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static com.tianji.promotion.utils.MyLockType.*;

@Component
public class MyLockFactory {
    private final Map<MyLockType, Function<String, RLock>> lockHandlersMap;

    public MyLockFactory(RedissonClient redissonClient){
        this.lockHandlersMap = new EnumMap<>(MyLockType.class);
        this.lockHandlersMap.put(RE_ENTRANT_LOCK, redissonClient::getLock);
        this.lockHandlersMap.put(FAIR_LOCK, redissonClient::getFairLock);
        this.lockHandlersMap.put(READ_LOCK, name -> redissonClient.getReadWriteLock(name).readLock());
        this.lockHandlersMap.put(WRITE_LOCK, name -> redissonClient.getReadWriteLock(name).writeLock());
    }

    public RLock getLock(MyLockType lockType, String name){
        Function<String, RLock> stringRLockFunction = lockHandlersMap.get(lockType);

        return stringRLockFunction.apply(name);
    }
}
