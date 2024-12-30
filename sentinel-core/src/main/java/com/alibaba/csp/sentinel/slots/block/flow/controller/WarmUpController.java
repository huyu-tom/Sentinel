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

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;

/**
 * <p>
 * The principle idea comes from Guava. However, the calculation of Guava is rate-based, which means
 * that we need to translate rate to QPS.
 * </p>
 *
 * <p>
 * Requests arriving at the pulse may drag down long idle systems even though it has a much larger
 * handling capability in stable period. It usually happens in scenarios that require extra time for
 * initialization, e.g. DB establishes a connection, connects to a remote service, and so on. That’s
 * why we need “warm up”.
 * </p>
 *
 * <p>
 * Sentinel's "warm-up" implementation is based on the Guava's algorithm. However, Guava’s
 * implementation focuses on adjusting the request interval, which is similar to leaky bucket.
 * Sentinel pays more attention to controlling the count of incoming requests per second without
 * calculating its interval, which resembles token bucket algorithm.
 * </p>
 *
 * <p>
 * The remaining tokens in the bucket is used to measure the system utility. Suppose a system can
 * handle b requests per second. Every second b tokens will be added into the bucket until the
 * bucket is full. And when system processes a request, it takes a token from the bucket. The more
 * tokens left in the bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 * </p>
 *
 * <p>
 * Base on Guava’s theory, there is a linear equation we can write this in the form y = m * x + b
 * where y (a.k.a y(x)), or qps(q)), is our expected QPS given a saturated period (e.g. 3 minutes
 * in), m is the rate of change from our cold (minimum) rate to our stable (maximum) rate, x (or q)
 * is the occupied token.
 * </p>
 *
 * @author jialiang.linjl
 */
public class WarmUpController implements TrafficShapingController {

  //flowRule中设置的阈值
  protected double count;

  //冷却因子
  private int coldFactor;

  //预警值
  protected int warningToken = 0;

  //最大可用的token值
  private int maxToken;

  //斜度
  protected double slope;

  //令牌数量
  protected AtomicLong storedTokens = new AtomicLong(0);

  //最后填充的时间
  protected AtomicLong lastFilledTime = new AtomicLong(0);

  // 次数,预热时间,冷却因子,斜率
  public WarmUpController(double count, int warmUpPeriodInSec, int coldFactor) {
    construct(count, warmUpPeriodInSec, coldFactor);
  }

  public WarmUpController(double count, int warmUpPeriodInSec) {
    construct(count, warmUpPeriodInSec, 3);
  }

  private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

    if (coldFactor <= 1) {
      throw new IllegalArgumentException("Cold factor should be larger than 1");
    }

    //个数
    this.count = count;

    //冷却因子
    this.coldFactor = coldFactor;

    //预热时间10s,然后规则制定每秒只能通过1000个请求,所以10s可通过10000个请求
    //然后有个预热阈值,当低于这个值的,表明有大流量来了,5000
    // thresholdPermits = 0.5 * warmupPeriod / stableInterval.
    // warningToken = 100;
    warningToken = (int) (warmUpPeriodInSec * count) / (coldFactor - 1);

    // / maxPermits = thresholdPermits + 2 * warmupPeriod /
    // (stableInterval + coldInterval)
    // maxToken = 200
    maxToken = warningToken + (int) (2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

    // slope
    // slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
    slope = (coldFactor - 1.0) / count / (maxToken - warningToken);
  }

  @Override
  public boolean canPass(Node node, int acquireCount) {
    return canPass(node, acquireCount, false);
  }

  @Override
  public boolean canPass(Node node, int acquireCount, boolean prioritized) {

    //现在1s的通过的qps
    long passQps = (long) node.passQps();

    // 采用了一分钟统计的滑动窗口,一共有60个窗口,一个窗口是1s中,获取上一个一秒的qps
    long previousQps = (long) node.previousPassQps();

    //生成token和移除上一秒的请求token
    syncToken(previousQps);

    // 开始计算它的斜率
    // 如果令牌桶中的token数量大于警戒值，说明还未预热结束，需要判断token的生成速度和消费速度
    long restToken = storedTokens.get();
    if (restToken >= warningToken) { //第一次进来他能拿到最大的maxToken,肯定是大于警戒值

      //在同一秒的时间之内,restToken的数据是一样的,所以aboveToken也是一样的
      //随着时间的推移,这个会越来越小,从而导致后面的生产的速率会提高
      long aboveToken = restToken - warningToken;

      // 消耗的速度要比warning快，但是要比慢
      // current interval = restToken * slope +  1 / count

      //计算此时一秒之内能够生成多少token的数量,在同一秒的该值也是一样的
      //1. (aboveToken * slope + (1.0 / count)) 防止生成token的个数大于count(该1s最大可以生成的token)
      // 1 / (500*slope(0.00004)+1/100) =   33.3333333333 = 34
      //2.  而且aboveToken为0的时候,  1/0 这是有问题的, 加上  1/1000 ,不就相当于1000
      double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));

      //passQps + acquireCount 代表这一秒的消费的速率
      //warningQps 代表是生产的速率
      //当生产的速率大于等于消费的速率,你就可以pass了
      if (passQps + acquireCount <= warningQps) {
        return true;
      }
    } else {
      //如果剩余令牌数小于警戒值，说明系统已经处于高水位，请求稳定，则直接判断QPS与阈值，超过阈值则限流
      if (passQps + acquireCount <= count) {
        return true;
      }
    }

    return false;
  }

  protected void syncToken(long passQps) {
    //判断当前时间是否要进行生成token,最后生成时间和现在要生成的时间是同一秒钟,就不用生成,如果不是,说明要重新生成新的token,同时将开始lastFilledTime是0,所以将开始是要生成的
    long currentTime = TimeUtil.currentTimeMillis();
    currentTime = currentTime - currentTime % 1000;
    long oldLastFillTime = lastFilledTime.get();
    if (currentTime <= oldLastFillTime) {
      return;
    }

    //进行生成token
    long oldValue = storedTokens.get();
    long newValue = coolDownTokens(currentTime, passQps);
    //减去上一秒的拿到令牌的qps,只有一个减
    if (storedTokens.compareAndSet(oldValue, newValue)) {
      long currentValue = storedTokens.addAndGet(0 - passQps);
      if (currentValue < 0) {
        storedTokens.set(0L);
      }
      lastFilledTime.set(currentTime);
    }
  }


  /**
   * 生成token
   *
   * @param currentTime
   * @param passQps
   * @return
   */
  private long coolDownTokens(long currentTime, long passQps) {
    //上一次还剩下多少token
    long oldValue = storedTokens.get();
    long newValue = oldValue;

    // 添加令牌的判断前提条件:
    if (oldValue < warningToken) {
      // 会出现以下2种情况
      // 1. 低于警戒线,正常生成token(说明达到了高水平了(可能还不稳定)),如果后期的时间还能继续达到的话,说明一直都很热,oldValue会越来越小或者永远都维持在<警戒线的情况,
      // 如果达到最高水平之后 情况一:  获取流量没有前期大,趋势是越来越少, 情况二: 直接跌入谷底(没有请求)(持续的时间可以通过currentTime - lastFilledTime.get() 来计算) 这样又会将系统带入冷系统的情况(说明oldValue会越来越大)
      // 2. 或者第一次进来的时候,lastFilledTime为0,oldValue也是0,所以newValue是一个很大的值,他是大于maxToken的值
      newValue = (long) (oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
    } else if (oldValue > warningToken) {
      //代表这段时间之内,passQps(消费的速率)都是低于(count/coldFactor)生产的速率,就还是正常的生成token
      //例如1s的qps是20,但是流量他是每秒只请求1次,而且这十秒之内或者几秒之内都是如此,那我们就当做还没有预热,当做下一次开始预热(可能下一次还是这样,也可能不是这样)
      if (passQps < (int) count / coldFactor) {
        // 1. 流量的特点: 一直都几乎没有达到
        // 没有达到冷启动的最低要求,例如1s中可以过20qps, 但是passQps是1 <  20 / 3 = 6
        // 上一次还是最大的token(oldValue) + (本次的count(有可能中间隔了几秒,就相当于隔了几秒没有去请求,份额得加上)
        // 最终大概率还是maxToken或者无限接近于maxToken

        // 2. 流量的特点: 一下子达到了,一下子又没有达到
        //但是达到了又不能不算,所以采用了普通生成token的算法,

        //而且一直低流量的话,会慢慢的蚕食掉token,使其通过了警戒线,从而可以一下子达到了最高的限流(这就是这块代码的作用)
        newValue = (long) (oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
      }
    }

    //防止生成的token大于了最大的token
    return Math.min(newValue, maxToken);
  }
}
