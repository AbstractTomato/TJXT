package com.tianji.promotion.utils;

import com.tianji.common.exceptions.BizIllegalException;
import org.redisson.api.RLock;

public enum MyLockStrategy {
    /**
     * 快速失败,没有获取锁,直接返回false
     */
    SKIP_FAST(){
        @Override
        public boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException {
            return rLock.tryLock(0, prop.leaseTime(), prop.unit());
        }
    },


    /**
     * 失败后抛出异常,没有获取锁,直接抛异常
     */
    FAIL_FAST() {
        @Override
        public boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException {
            boolean isLock = rLock.tryLock(0, prop.leaseTime(), prop.unit());
            if (!isLock){
                throw new BizIllegalException("请求太频繁!");
            }

            return true;
        }
    },


    /**
     * 一直尝试获取锁
     */
    KEEP_TRYING(){
        @Override
        public boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException {
            rLock.lock(prop.leaseTime(), prop.unit());
            return true;
        }
    },


    /**
     * 尝试后,直接返回结果
     */
    SKIP_AFTER_RETRY_TIMEOUT(){
        @Override
        public boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException {
            return rLock.tryLock(prop.waitTime(), prop.leaseTime(), prop.unit());
        }
    },

    /**
     * 尝试后,抛出异常
     */
    FAIL_AFTER_RETRY_TIMEOUT(){
        @Override
        public boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException {
            boolean isLock = rLock.tryLock(prop.waitTime(), prop.leaseTime(), prop.unit());
            if (!isLock){
                throw new BizIllegalException("请求太频繁!");
            }
            return true;
        }
    },
    ;

    public abstract boolean tryLock(RLock rLock, MyLock prop) throws InterruptedException;
}
