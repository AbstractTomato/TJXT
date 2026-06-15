package com.tianji.learning.utils;

import lombok.Data;

import java.time.Duration;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

@Data
public class DelayTask<D> implements Delayed {

    //实际要延迟处理的数据
    private D data;
    //任务的绝对到期时间
    private long deadlineNanos;

    public DelayTask(D data, Duration delayTime) {
        this.data = data;
        this.deadlineNanos = System.nanoTime() + delayTime.toNanos();
    }

    //返回剩余延迟时间
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    //定义任务在队列中的排序规则.剩余延迟时间越短,越排在队头
    @Override
    public int compareTo(Delayed o) {
        long l = getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS);

        if (l > 0){
            return 1;
        }else if (l < 0){
            return -1;
        }else {
            return 0;
        }
    }
}
