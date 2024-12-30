/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * 响应时间(啥响应时间是慢请求,并且慢请求占用所有请求的比例是如何的)
 *
 * @author Eric Zhao
 * @since 1.8.0
 */
public class ResponseTimeCircuitBreaker extends AbstractCircuitBreaker {

    private static final double SLOW_REQUEST_RATIO_MAX_VALUE = 1.0d;

    //定义了该资源<=该响应时间的都是慢请求
    private final long maxAllowedRt;

    //慢请求的比率
    private final double maxSlowRequestRatio;

    //大于该值,并且阈值达到了,才会触发熔断机制
    private final int minRequestAmount;

    //用于统计数据(滑动窗口)
    private final LeapArray<SlowRequestCounter> slidingCounter;

    public ResponseTimeCircuitBreaker(DegradeRule rule) {
        this(rule, new SlowRequestLeapArray(1, rule.getStatIntervalMs()));
    }

    ResponseTimeCircuitBreaker(DegradeRule rule, LeapArray<SlowRequestCounter> stat) {
        super(rule);
        AssertUtil.isTrue(rule.getGrade() == RuleConstant.DEGRADE_GRADE_RT, "rule metric type should be RT");
        AssertUtil.notNull(stat, "stat cannot be null");
        this.maxAllowedRt = Math.round(rule.getCount());
        this.maxSlowRequestRatio = rule.getSlowRatioThreshold();
        this.minRequestAmount = rule.getMinRequestAmount();
        this.slidingCounter = stat;
    }

    @Override
    public void resetStat() {
        // Reset current bucket (bucket count = 1).
        slidingCounter.currentWindow().value().reset();
    }

    @Override
    public void onRequestComplete(Context context) {

        //进行统计,慢请求的个数和总请求的个数
        SlowRequestCounter counter = slidingCounter.currentWindow().value();
        Entry entry = context.getCurEntry();
        if (entry == null) {
            return;
        }

        //慢请求 > 指定的那个响应时间
        long completeTime = entry.getCompleteTimestamp();
        if (completeTime <= 0) {
            completeTime = TimeUtil.currentTimeMillis();
        }
        long rt = completeTime - entry.getCreateTimestamp();
        if (rt > maxAllowedRt) {
            counter.slowCount.add(1);
        }
        counter.totalCount.add(1);


        handleStateChangeWhenThresholdExceeded(rt);
    }

    private void handleStateChangeWhenThresholdExceeded(long rt) {
        if (currentState.get() == State.OPEN) {
            //如果已经打开了,就直接放行
            return;
        }

        //如果是半开,当前请求是个啥状态(是否是慢请求),是的话,就变成打开
        //如果不是的话,就关闭
        if (currentState.get() == State.HALF_OPEN) {
            // In detecting request
            // TODO: improve logic for half-open recovery
            if (rt > maxAllowedRt) {
                fromHalfOpenToOpen(1.0d);
            } else {
                fromHalfOpenToClose();
            }
            return;
        }

        //再来按照比例操作
        List<SlowRequestCounter> counters = slidingCounter.values();
        long slowCount = 0;
        long totalCount = 0;
        for (SlowRequestCounter counter : counters) {
            slowCount += counter.slowCount.sum();
            totalCount += counter.totalCount.sum();
        }
        if (totalCount < minRequestAmount) {
            return;
        }
        double currentRatio = slowCount * 1.0d / totalCount;
        if (currentRatio > maxSlowRequestRatio) {
            //如果达到了最大的比例,就变成打开
            transformToOpen(currentRatio);
        }


        if (Double.compare(currentRatio, maxSlowRequestRatio) == 0 && Double.compare(maxSlowRequestRatio, SLOW_REQUEST_RATIO_MAX_VALUE) == 0) {
            transformToOpen(currentRatio);
        }
    }

    static class SlowRequestCounter {
        private LongAdder slowCount;
        private LongAdder totalCount;

        public SlowRequestCounter() {
            this.slowCount = new LongAdder();
            this.totalCount = new LongAdder();
        }

        public LongAdder getSlowCount() {
            return slowCount;
        }

        public LongAdder getTotalCount() {
            return totalCount;
        }

        public SlowRequestCounter reset() {
            slowCount.reset();
            totalCount.reset();
            return this;
        }

        @Override
        public String toString() {
            return "SlowRequestCounter{" + "slowCount=" + slowCount + ", totalCount=" + totalCount + '}';
        }
    }

    static class SlowRequestLeapArray extends LeapArray<SlowRequestCounter> {

        public SlowRequestLeapArray(int sampleCount, int intervalInMs) {
            super(sampleCount, intervalInMs);
        }

        @Override
        public SlowRequestCounter newEmptyBucket(long timeMillis) {
            return new SlowRequestCounter();
        }

        @Override
        protected WindowWrap<SlowRequestCounter> resetWindowTo(WindowWrap<SlowRequestCounter> w, long startTime) {
            w.resetTo(startTime);
            w.value().reset();
            return w;
        }
    }
}
