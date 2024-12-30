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
package com.alibaba.csp.sentinel.slots.block.flow.param;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.statistic.cache.CacheMap;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Rule checker for parameter flow control.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
public final class ParamFlowChecker {

  public static boolean passCheck(ResourceWrapper resourceWrapper, /*@Valid*/
      ParamFlowRule rule, /*@Valid*/ int count, Object... args) {
    if (args == null) {
      return true;
    }

    int paramIdx = rule.getParamIdx();
    if (args.length <= paramIdx) {
      return true;
    }

    // Get parameter value.
    Object value = args[paramIdx];

    // Assign value with the result of paramFlowKey method
    if (value instanceof ParamFlowArgument) {
      value = ((ParamFlowArgument) value).paramFlowKey();
    }
    // If value is null, then pass
    if (value == null) {
      return true;
    }

    //如果是集群,走集群的逻辑(网络请求)
    if (rule.isClusterMode() && rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
      return passClusterCheck(resourceWrapper, rule, count, value);
    }

    return passLocalCheck(resourceWrapper, rule, count, value);
  }

  private static boolean passLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int count, Object value) {
    try {
      if (Collection.class.isAssignableFrom(value.getClass())) {
        for (Object param : ((Collection) value)) {
          if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
            return false;
          }
        }
      } else if (value.getClass().isArray()) {
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
          Object param = Array.get(value, i);
          if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
            return false;
          }
        }
      } else {
        return passSingleValueCheck(resourceWrapper, rule, count, value);
      }
    } catch (Throwable e) {
      RecordLog.warn("[ParamFlowChecker] Unexpected error", e);
    }

    return true;
  }

  static boolean passSingleValueCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int acquireCount, Object value) {
    if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
      if (rule.getControlBehavior() == RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER) {
        //匀速(漏桶算法)
        return passThrottleLocalCheck(resourceWrapper, rule, acquireCount, value);
      } else {
        //令牌桶算法(记住最后一次生成token的时间,记录最后生成token的时间段的token个数)
        return passDefaultLocalCheck(resourceWrapper, rule, acquireCount, value);
      }
    } else if (rule.getGrade() == RuleConstant.FLOW_GRADE_THREAD) {
      //线程数(能执行此方法说明就是一次线程(但是在jdk21的时候,虚拟线程(成本较低)))
      Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
      long threadCount = getParameterMetric(resourceWrapper).getThreadCount(rule.getParamIdx(),
          value);
      if (exclusionItems.contains(value)) {
        int itemThreshold = rule.getParsedHotItems().get(value);
        return ++threadCount <= itemThreshold;
      }
      long threshold = (long) rule.getCount();
      return ++threadCount <= threshold;
    }

    return true;
  }

  static boolean passDefaultLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int acquireCount, Object value) {
    // 一个资源对应多个
    ParameterMetric metric = getParameterMetric(resourceWrapper);

    //对于某个热点数据值,在某一秒区间剩余的token次数
    CacheMap<Object, AtomicLong> tokenCounters =
        metric == null ? null : metric.getRuleTokenCounter(rule);

    //当前最后一秒的生成
    CacheMap<Object, AtomicLong> timeCounters =
        metric == null ? null : metric.getRuleTimeCounter(rule);

    if (tokenCounters == null || timeCounters == null) {
      return true;
    }

    // Calculate max token count (threshold)
    Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
    long tokenCount = (long) rule.getCount();
    if (exclusionItems.contains(value)) {
      tokenCount = rule.getParsedHotItems().get(value);
    }

    if (tokenCount == 0) {
      return false;
    }

    long maxCount = tokenCount + rule.getBurstCount();
    if (acquireCount > maxCount) {
      return false;
    }

    while (true) {
      long currentTime = TimeUtil.currentTimeMillis();

      AtomicLong lastAddTokenTime = timeCounters.putIfAbsent(value, new AtomicLong(currentTime));
      if (lastAddTokenTime == null) {
        // Token never added, just replenish the tokens and consume {@code acquireCount} immediately.
        tokenCounters.putIfAbsent(value, new AtomicLong(maxCount - acquireCount));
        return true;
      }

      // Calculate the time duration since last token was added.
      long passTime = currentTime - lastAddTokenTime.get();
      // A simplified token bucket algorithm that will replenish the tokens only when statistic window has passed.
      if (passTime > rule.getDurationInSec() * 1000) {
        //要生成新的token了
        AtomicLong oldQps = tokenCounters.putIfAbsent(value,
            new AtomicLong(maxCount - acquireCount));
        if (oldQps == null) {
          // Might not be accurate here.
          lastAddTokenTime.set(currentTime);
          return true;
        } else {
          //上一次剩下的token个数
          long restQps = oldQps.get();

          //计算本次要生成的token个数
          long toAddCount = (passTime * tokenCount) / (rule.getDurationInSec() * 1000);
          long newQps = toAddCount + restQps > maxCount ? (maxCount - acquireCount)
              : (restQps + toAddCount - acquireCount);

          if (newQps < 0) {
            return false;
          }
          if (oldQps.compareAndSet(restQps, newQps)) {
            lastAddTokenTime.set(currentTime);
            return true;
          }
          Thread.yield();
        }
      } else {
        //这里还不能生成token,用之前生成的
        AtomicLong oldQps = tokenCounters.get(value);
        if (oldQps != null) {
          long oldQpsValue = oldQps.get();
          if (oldQpsValue - acquireCount >= 0) {
            //如果设置成功,说明可以通过
            if (oldQps.compareAndSet(oldQpsValue, oldQpsValue - acquireCount)) {
              return true;
            }
          } else {
            //说明不能通过,当前的token的次数不足以抵扣当前要消费的token次数
            return false;
          }
        }
        Thread.yield();
      }
    }
  }

  static boolean passThrottleLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int acquireCount, Object value) {
    ParameterMetric metric = getParameterMetric(resourceWrapper);
    CacheMap<Object, AtomicLong> timeRecorderMap =
        metric == null ? null : metric.getRuleTimeCounter(rule);
    if (timeRecorderMap == null) {
      return true;
    }

    // Calculate max token count (threshold)
    //获取特殊值设置的次数
    Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
    long tokenCount = (long) rule.getCount();
    if (exclusionItems.contains(value)) {
      tokenCount = rule.getParsedHotItems().get(value);
    }

    if (tokenCount == 0) {
      return false;
    }

    //1次token需要多长时间, 然后该次请求需要消耗多长时间(该次请求需要多少个token)
    long costTime = Math.round(1.0 * 1000 * acquireCount * rule.getDurationInSec() / tokenCount);
    while (true) {

      //当前时间
      long currentTime = TimeUtil.currentTimeMillis();
      AtomicLong timeRecorder = timeRecorderMap.putIfAbsent(value, new AtomicLong(currentTime));
      if (timeRecorder == null) {
        return true;
      }

      //AtomicLong timeRecorder = timeRecorderMap.get(value);
      long lastPassTime = timeRecorder.get();
      long expectedTime = lastPassTime + costTime;

      if (expectedTime <= currentTime || expectedTime - currentTime < rule.getMaxQueueingTimeMs()) {
        AtomicLong lastPastTimeRef = timeRecorderMap.get(value);
        if (lastPastTimeRef.compareAndSet(lastPassTime, currentTime)) {
          long waitTime = expectedTime - currentTime;
          if (waitTime > 0) {
            //休眠一段时间, 还未到期望的时间
            lastPastTimeRef.set(expectedTime);
            try {
              TimeUnit.MILLISECONDS.sleep(waitTime);
            } catch (InterruptedException e) {
              RecordLog.warn("passThrottleLocalCheck: wait interrupted", e);
            }
          }
          //到了期望的时间,就直接pass通过
          return true;
        } else {
          //设置失败,说明并发冲突比较多,这里放弃当前线程的执行权(但是还会争夺执行权)
          Thread.yield();
        }
      } else {
        return false;
      }
    }
  }

  private static ParameterMetric getParameterMetric(ResourceWrapper resourceWrapper) {
    // Should not be null.
    return ParameterMetricStorage.getParamMetric(resourceWrapper);
  }

  @SuppressWarnings("unchecked")
  private static Collection<Object> toCollection(Object value) {
    if (value instanceof Collection) {
      return (Collection<Object>) value;
    } else if (value.getClass().isArray()) {
      List<Object> params = new ArrayList<Object>();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        Object param = Array.get(value, i);
        params.add(param);
      }
      return params;
    } else {
      return Collections.singletonList(value);
    }
  }

  private static boolean passClusterCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int count, Object value) {
    try {
      Collection<Object> params = toCollection(value);

      //pick
      TokenService clusterService = pickClusterService();
      if (clusterService == null) {
        // No available cluster client or server, fallback to local or
        // pass in need.
        // 如果没有服务,就走本地操作
        return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
      }

      //挑选了一个服务
      TokenResult result = clusterService.requestParamToken(rule.getClusterConfig().getFlowId(),
          count, params);
      switch (result.getStatus()) {
        case TokenResultStatus.OK:
          return true;
        case TokenResultStatus.BLOCKED:
          return false;
        default:
          //如果出现异常的情况,走本地的情况(来进行兜底)
          return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
      }
    } catch (Throwable ex) {
      RecordLog.warn("[ParamFlowChecker] Request cluster token for parameter unexpected failed",
          ex);
      return fallbackToLocalOrPass(resourceWrapper, rule, count, value);
    }
  }

  private static boolean fallbackToLocalOrPass(ResourceWrapper resourceWrapper, ParamFlowRule rule,
      int count, Object value) {
    if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
      return passLocalCheck(resourceWrapper, rule, count, value);
    } else {
      // The rule won't be activated, just pass.
      return true;
    }
  }

  private static TokenService pickClusterService() {
    //客户端和服务端分开部署
    if (ClusterStateManager.isClient()) {
      return TokenClientProvider.getClient();
    }

    //嵌入部署,有可能客服端和服务商都是一个机器
    if (ClusterStateManager.isServer()) {
      return EmbeddedClusterTokenServerProvider.getServer();
    }
    return null;
  }

  private ParamFlowChecker() {
  }
}
