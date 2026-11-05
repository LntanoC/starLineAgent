/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.starlineagent.mcp.executor.starline.event;

import java.util.*;

public final class CoverageReporter {
    private CoverageReporter() {}
    public static List<String> notes(Map<String, Object> coverage, int unknownDates, int stale, int conflicts, int remaining) {
        List<String> notes = new ArrayList<>();
        notes.add("检索窗口：" + coverage.get("window") + "；本次检索 " + ((List<?>) coverage.get("queries")).size()
                + " 个查询、" + ((List<?>) coverage.get("sources")).size() + " 个已配置来源，原始 " + coverage.get("rawResults") + " 条页面结果。逐层页面数：白名单后 "
                + coverage.get("afterSource") + "、相关性后 " + coverage.get("afterRelevance") + "、日期/城市筛选后 " + coverage.get("afterDate") + "；合并后 "
                + coverage.get("merged") + " 条场次，本页展示 " + coverage.get("returned") + " 条。");
        notes.add("过滤/去重计数：" + coverage.get("filtered") + "（场次拆分及页面过滤单位见 coverage.filterUnits）。");
        if (remaining > 0) notes.add("还有 " + remaining + " 条未展示，可加载更多；分页来自本次检索快照。");
        if (unknownDates > 0) notes.add(unknownDates + " 条日期待核实，已保留；日期候选不能作为确定场次或提醒依据。");
        if (stale > 0) notes.add(stale + " 条来源发布时间较早，请到来源页重新核验。");
        if (conflicts > 0) notes.add(conflicts + " 条信息存在冲突，不能据此确认开场、购票或创建提醒。");
        if (((Map<?, ?>) coverage.get("filtered")).containsKey("REDIRECT_UNRESOLVED")) notes.add("部分非白名单链接的跳转无法在安全/时间预算内核验，覆盖可能不完整。");
        var failures = (List<?>) coverage.get("failures");
        if (!failures.isEmpty()) notes.add("检索失败/预算限制：" + failures + "，覆盖可能不完整；已保留其他成功结果。");
        if (Boolean.TRUE.equals(coverage.get("providerPageLimit"))) notes.add("搜索提供方存在单页或分页预算上限，覆盖可能不完整。");
        if ("UNKNOWN".equals(coverage.get("completeness"))) notes.add("没有可用来源或搜索提供方，本次没有执行检索，覆盖未知。");
        notes.add("仅表示在已启用来源、查询预算和窗口内本次检索到的结果；未查到不代表没有演出。GOV仅证明许可，购票状态请以票务来源为准。");
        return notes;
    }
}
