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
package com.alibaba.csp.sentinel.slots.block.authority;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.util.StringUtil;

/**
 * Rule checker for white/black list authority.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
final class AuthorityRuleChecker {

    static boolean passCheck(AuthorityRule rule, Context context) {
        //来源
        String requester = context.getOrigin();

        // Empty origin or empty limitApp will pass.
        if (StringUtil.isEmpty(requester) || StringUtil.isEmpty(rule.getLimitApp())) {
            return true;
        }

        // Do exact match with origin name.
        // limitApp
        int pos = rule.getLimitApp().indexOf(requester);

        boolean contain = pos > -1;

        if (contain) {
            //包含了(app包含了来源)
            boolean exactlyMatch = false;
            // app可以用逗号隔开
            String[] appArray = rule.getLimitApp().split(",");
            //是否是完全匹配
            for (String app : appArray) {
                if (requester.equals(app)) {
                    exactlyMatch = true;
                    break;
                }
            }
            //是完全匹配就是返回true,不是完全匹配就是false
            contain = exactlyMatch;
        }

        int strategy = rule.getStrategy();
        //如果是黑名单且包含,就直接不让通过
        if (strategy == RuleConstant.AUTHORITY_BLACK && contain) {
            return false;
        }

        //如果是白名单,且不包含,也不让通过
        if (strategy == RuleConstant.AUTHORITY_WHITE && !contain) {
            return false;
        }

        return true;
    }

    private AuthorityRuleChecker() {
    }
}
