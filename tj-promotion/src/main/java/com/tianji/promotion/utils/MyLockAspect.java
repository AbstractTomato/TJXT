package com.tianji.promotion.utils;

import com.tianji.common.exceptions.BizIllegalException;
import io.lettuce.core.RedisClient;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
@Aspect
@RequiredArgsConstructor
public class MyLockAspect implements Ordered {

    private final MyLockFactory lockFactory;

    @Around("@annotation(myLock)")
    public Object tryLock(ProceedingJoinPoint joinPoint, MyLock myLock) throws Throwable {
        //1.创建锁对象
        String name = myLock.name();
        RLock redissonClientLock = lockFactory.getLock(myLock.lockType(), name);
        //2.尝试获取锁
        boolean isLock = myLock.lockStrategy().tryLock(redissonClientLock, myLock);
        //3.判断是否成功
        if (!isLock){
            //3.1.如果失败,快速结束
            return null;
        }
        try {
            //3.2.如果成功,执行业务
            return joinPoint.proceed();
        }finally {
            //4.释放锁
            redissonClientLock.unlock();
        }

    }

    /**
     * 要让加锁的优先级高于事务
     * @return
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
