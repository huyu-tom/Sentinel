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
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.util.AssertUtil;

import static com.alibaba.csp.sentinel.slots.block.RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT;
import static com.alibaba.csp.sentinel.slots.block.RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO;

/**
 * @author Eric Zhao
 * @since 1.8.0
 */
public class ExceptionCircuitBreaker extends AbstractCircuitBreaker {

    //异常数和异常比例(记录总数和当前异常数)
    private final int strategy;

    //最小请求
    private final int minRequestAmount;

    //异常数(0,无穷大)或者异常比例(0,1]
    private final double threshold;

    //用来统计异常数和异常比例
    private final LeapArray<SimpleErrorCounter> stat;

    public ExceptionCircuitBreaker(DegradeRule rule) {
        this(rule, new SimpleErrorCounterLeapArray(1, rule.getStatIntervalMs()));
    }

    ExceptionCircuitBreaker(DegradeRule rule, LeapArray<SimpleErrorCounter> stat) {
        super(rule);
        this.strategy = rule.getGrade();
        boolean modeOk = strategy == DEGRADE_GRADE_EXCEPTION_RATIO || strategy == DEGRADE_GRADE_EXCEPTION_COUNT;
        AssertUtil.isTrue(modeOk, "rule strategy should be error-ratio or error-count");
        AssertUtil.notNull(stat, "stat cannot be null");
        this.minRequestAmount = rule.getMinRequestAmount();
        this.threshold = rule.getCount();
        this.stat = stat;
    }

    @Override
    protected void resetStat() {
        // Reset current bucket (bucket count = 1).
        stat.currentWindow().value().reset();
    }

    @Override
    public void onRequestComplete(Context context) {
        Entry entry = context.getCurEntry();
        if (entry == null) {
            return;
        }

        //异常数  -> 在单位时间里面的异常数和总的请求数
        Throwable error = entry.getError();
        SimpleErrorCounter counter = stat.currentWindow().value();
        if (error != null) {
            counter.getErrorCount().add(1);
        }
        counter.getTotalCount().add(1);

        handleStateChangeWhenThresholdExceeded(error);
    }


    /**
     * 当阈值超过处理状态的变化(熔断器的关闭,半开,打开)
     *
     * @param error
     */
    private void handleStateChangeWhenThresholdExceeded(Throwable error) {
        //如果是打开状态,就直接返回
        if (currentState.get() == State.OPEN) {
            return;
        }

        //如果是半开状态(说明可能这个是放了那个流量)
        if (currentState.get() == State.HALF_OPEN) {
            // In detecting request
            if (error == null) {
                //这个流量没有出现异常,将其变为关闭
                fromHalfOpenToClose();
            } else {
                //这个流量出现了异常,就变成打开
                fromHalfOpenToOpen(1.0d);
            }
            return;
        }

        //这里是关闭的状态,看看现在是个什么请看
        List<SimpleErrorCounter> counters = stat.values();
        long errCount = 0;
        long totalCount = 0;
        for (SimpleErrorCounter counter : counters) {
            errCount += counter.errorCount.sum();
            totalCount += counter.totalCount.sum();
        }

        //如果小于最小请求数,连最小请求数都没有达到
        if (totalCount < minRequestAmount) {
            return;
        }

        //
        double curCount = errCount;

        //如果当前的策略是异常频率
        if (strategy == DEGRADE_GRADE_EXCEPTION_RATIO) {
            // Use errorRatio
            curCount = errCount * 1.0d / totalCount;
        }

        //如果大于指定的频率,说明就变成了打开了,或者是策略是异常个数，如果异常个数>指定的个数,说明也打开了
        if (curCount > threshold) {
            transformToOpen(curCount);
        }
    }

    static class SimpleErrorCounter {
        private LongAdder errorCount;
        private LongAdder totalCount;

        public SimpleErrorCounter() {
            this.errorCount = new LongAdder();
            this.totalCount = new LongAdder();
        }

        public LongAdder getErrorCount() {
            return errorCount;
        }

        public LongAdder getTotalCount() {
            return totalCount;
        }

        public SimpleErrorCounter reset() {
            errorCount.reset();
            totalCount.reset();
            return this;
        }

        @Override
        public String toString() {
            return "SimpleErrorCounter{" + "errorCount=" + errorCount + ", totalCount=" + totalCount + '}';
        }
    }

    static class SimpleErrorCounterLeapArray extends LeapArray<SimpleErrorCounter> {

        public SimpleErrorCounterLeapArray(int sampleCount, int intervalInMs) {
            super(sampleCount, intervalInMs);
        }

        @Override
        public SimpleErrorCounter newEmptyBucket(long timeMillis) {
            return new SimpleErrorCounter();
        }

        @Override
        protected WindowWrap<SimpleErrorCounter> resetWindowTo(WindowWrap<SimpleErrorCounter> w, long startTime) {
            // Update the start time and reset value.
            w.resetTo(startTime);
            w.value().reset();
            return w;
        }
    }
}
