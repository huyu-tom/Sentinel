/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * @author jialiang.linjl
 * @since 1.4.0
 */
public class WarmUpRateLimiterController extends WarmUpController {

    /**
     * 最大的等待时间
     */
    private final int timeoutInMs;

    /**
     * 记录最后一次请求通过的时间
     */
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public WarmUpRateLimiterController(double count, int warmUpPeriodSec, int timeOutMs, int coldFactor) {
        super(count, warmUpPeriodSec, coldFactor);
        this.timeoutInMs = timeOutMs;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }


    /**
     * 基于速率的冷启动限流算法
     *
     * @param node
     * @param acquireCount
     * @param prioritized
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        long previousQps = (long) node.previousPassQps();
        syncToken(previousQps);

        long currentTime = TimeUtil.currentTimeMillis();

        long restToken = storedTokens.get();
        long costTime = 0;
        long expectedTime = 0;
        if (restToken >= warningToken) {
            //这个在其父类的有介绍个这方面的逻辑含义
            long aboveToken = restToken - warningToken;
            // current interval = restToken*slope+1/count
            double warmingQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            costTime = Math.round(1.0 * (acquireCount) / warmingQps * 1000);
        } else {
            costTime = Math.round(1.0 * (acquireCount) / count * 1000);
        }


        //期望时间
        expectedTime = costTime + latestPassedTime.get();


        //如果该请求的期望时间小于当前的时间,就直接通过,并且把当前时间设置为最后的通过的时间
        if (expectedTime <= currentTime) {
            latestPassedTime.set(currentTime);
            return true;
        } else {
            //如果期望时间>当前时间,说明要休眠了
            long waitTime = costTime + latestPassedTime.get() - currentTime;

            //如果当前要休眠的时间大于最大的休眠的时间,就直接不通过
            if (waitTime > timeoutInMs) {
                return false;
            } else {
                //如果是小于我们设置最大的休眠时间

                //将上次的最后时间加上本次休眠的时间就是当前请求最后通过的时间,并且返回最新的时间
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    //因为上一次操作可以存在多个线程的并发操作,可能oldValue可能不是我们期待的正确的值,需要再次判断一次
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    //判断等待的时间是否大于最大的等待的时间
                    if (waitTime > timeoutInMs) {
                        //如果大于最大额的等待的时间,就将之前加上去的时间进行扣减,并且是不允许通过的
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
                    if (waitTime > 0) {
                        //进行休眠
                        Thread.sleep(waitTime);
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }
}
