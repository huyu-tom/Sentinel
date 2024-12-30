/*
 * Copyright 1999-2022 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * 漏斗算法
 * 无论请求量又多大,最终都是以均匀的速度流出,
 * 假设 100q/s ,假设一秒最多达到100的qps，那么每个请求过去是需要10ms的,记录一下最后一次请求的时间
 *
 * @author Eric Zhao
 * @author jialiang.linjl
 * @since 2.0
 */
public class ThrottlingController implements TrafficShapingController {

    // Refactored from legacy RateLimitController of Sentinel 1.x.

    private static final long MS_TO_NS_OFFSET = TimeUnit.MILLISECONDS.toNanos(1);

    //最大的等待时间
    private final int maxQueueingTimeMs;

    //时间间隔
    private final int statDurationMs;

    //在statDurationMs指定的时间间隔之内可以通过多少个请求
    private final double count;

    //是否使用纳秒,相对来说比较正确
    private final boolean useNanoSeconds;

    //最后通过的时间
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public ThrottlingController(int queueingTimeoutMs, double maxCountPerStat) {
        this(queueingTimeoutMs, maxCountPerStat, 1000);
    }

    public ThrottlingController(int queueingTimeoutMs, double maxCountPerStat, int statDurationMs) {
        AssertUtil.assertTrue(statDurationMs > 0, "statDurationMs should be positive");
        AssertUtil.assertTrue(maxCountPerStat >= 0, "maxCountPerStat should be >= 0");
        AssertUtil.assertTrue(queueingTimeoutMs >= 0, "queueingTimeoutMs should be >= 0");

        //最大的等待时间(ms),如果需要等待的时间过大,肯定会拖累系统,就直接拒绝
        this.maxQueueingTimeMs = queueingTimeoutMs;


        //控制速率(例如100q/s , 1s只允许通过100个请求,所以说一个请求的时间间隔这里大概是10ms)
        //最大的次数
        this.count = maxCountPerStat;
        //时间间隔
        this.statDurationMs = statDurationMs;

        // Use nanoSeconds when durationMs%count != 0 or count/durationMs> 1 (to be accurate)
        if (maxCountPerStat > 0) {
            this.useNanoSeconds = statDurationMs % Math.round(maxCountPerStat) != 0 || maxCountPerStat / statDurationMs > 1;
        } else {
            this.useNanoSeconds = false;
        }
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }


    /**
     * @param acquireCount
     * @param maxCountPerStat
     * @return
     */
    private boolean checkPassUsingNanoSeconds(int acquireCount, double maxCountPerStat) {
        //最大等待纳秒
        final long maxQueueingTimeNs = maxQueueingTimeMs * MS_TO_NS_OFFSET;

        //获取当前请求的纳秒
        long currentTime = System.nanoTime();


        //该请求需要消耗的时间
        // Calculate the interval between every two requests. 计算出每2个请求之间的需要等待的时间
        final long costTimeNs = Math.round(1.0d * MS_TO_NS_OFFSET * statDurationMs * acquireCount / maxCountPerStat);

        // Expected pass time of this request. 此次请求预期通过的时间
        long expectedTime = costTimeNs + latestPassedTime.get();


        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            //期望的可以过的时间正好小于等于当前的时间,说明可以通过
            latestPassedTime.set(currentTime);
            return true;
        } else {
            final long curNanos = System.nanoTime();
            // Calculate the time to wait. 期望时间-当前时间 变成了需要等待的时间,为什么要重新计算,可能希望正确一点(因为是多线程通行(对于latestPassedTime变量的修改))
            long waitTime = costTimeNs + latestPassedTime.get() - curNanos;

            //如果等待的时间大于我们的最大的等待时间,那我们就直接拒绝
            if (waitTime > maxQueueingTimeNs) {
                return false;
            }


            //这个请求应该在的时间点,因为记录了最后一次的时间加上现在这个请求应该消耗的时间
            long oldTime = latestPassedTime.addAndGet(costTimeNs);


            //这次请求应该的请求时间减去当前的时间得到一个需要等待的时间, 这个等待时候可能是负数,为负数就直接放行
            waitTime = oldTime - curNanos;

            //需要等待的时间大于最大的等待时间,直接拒绝
            if (waitTime > maxQueueingTimeNs) {
                //并且将之前加上的时间进行扣减掉
                latestPassedTime.addAndGet(-costTimeNs);
                return false;
            }


            // in race condition waitTime may <= 0
            // 进行休眠
            if (waitTime > 0) {
                sleepNanos(waitTime);
            }
            return true;
        }
    }


    /**
     * @param acquireCount    当前尝试采用多少次数
     * @param maxCountPerStat 最大的计数
     * @return
     */
    private boolean checkPassUsingCachedMs(int acquireCount, double maxCountPerStat) {
        long currentTime = TimeUtil.currentTimeMillis();


        // Calculate the interval between every two requests.
        //计算每2个请求之间的间隔
        long costTime = Math.round(1.0d * statDurationMs * acquireCount / maxCountPerStat);

        // Expected pass time of this request.
        long expectedTime = costTime + latestPassedTime.get();

        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            latestPassedTime.set(currentTime);
            return true;
        } else {
            // Calculate the time to wait. 计算等待时间
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            if (waitTime > maxQueueingTimeMs) {
                return false;
            }

            long oldTime = latestPassedTime.addAndGet(costTime);
            waitTime = oldTime - TimeUtil.currentTimeMillis();
            if (waitTime > maxQueueingTimeMs) {
                latestPassedTime.addAndGet(-costTime);
                return false;
            }
            // in race condition waitTime may <= 0
            if (waitTime > 0) {
                sleepMs(waitTime);
            }
            return true;
        }
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise, the costTime will be max of long and waitTime will overflow in some cases.
        if (count <= 0) {
            return false;
        }
        if (useNanoSeconds) {
            return checkPassUsingNanoSeconds(acquireCount, this.count);
        } else {
            return checkPassUsingCachedMs(acquireCount, this.count);
        }
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private void sleepNanos(long ns) {
        LockSupport.parkNanos(ns);
    }
}
