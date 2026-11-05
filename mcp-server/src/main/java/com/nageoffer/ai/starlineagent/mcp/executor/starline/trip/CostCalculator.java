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

package com.nageoffer.ai.starlineagent.mcp.executor.starline.trip;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import static com.nageoffer.ai.starlineagent.mcp.executor.starline.trip.JourneyModels.*;

public final class CostCalculator {
    private CostCalculator() {}
    public static CostSummary summarize(List<CostItem> items, int partySize) {
        if (partySize < 1 || partySize > 20) throw new IllegalArgumentException("同行人数须为 1 到 20");
        long min = 0, max = 0;
        boolean known = false;
        for (CostItem item : items) {
            if (!"CNY".equals(item.currency())) throw new IllegalArgumentException("暂不支持混合币种汇总");
            if (item.status() == CostStatus.UNKNOWN) continue;
            known = true;
            int count = item.scope() == CostScope.PER_PERSON ? partySize : 1;
            min = Math.addExact(min, Math.multiplyExact(item.minMinor(), count));
            max = Math.addExact(max, Math.multiplyExact(item.maxMinor(), count));
        }
        return new CostSummary(List.copyOf(items), min, known ? min : null, known ? max : null,
                known ? divide(min, partySize) : null, known ? divide(max, partySize) : null,
                items.stream().filter(item -> item.status() == CostStatus.UNKNOWN).map(CostItem::category).distinct().toList(),
                "CNY", partySize);
    }
    private static long divide(long amount, int count) {
        return BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP).longValueExact();
    }
    public static Long minor(String yuan) {
        if (yuan == null || !yuan.matches("\\d{1,8}(\\.\\d{1,2})?")) return null;
        try { return new BigDecimal(yuan).movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { return null; }
    }
    public static CostItem fuel(int distanceMeters, BigDecimal litresPer100km, BigDecimal yuanPerLitre) {
        if (distanceMeters < 0 || litresPer100km.signum() <= 0 || yuanPerLitre.signum() <= 0)
            throw new IllegalArgumentException("油费估算参数无效");
        long amount = BigDecimal.valueOf(distanceMeters).multiply(litresPer100km).multiply(yuanPerLitre)
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact();
        return new CostItem("fuel", "油费（去程）", amount, amount, "CNY", CostStatus.ESTIMATED,
                CostScope.PER_VEHICLE, "formula:distanceMeters/100000*litresPer100km*yuanPerLitre*100",
                "按百公里 " + litresPer100km + " L、" + yuanPerLitre + " 元/L 的配置估算；不是实时油价");
    }
}
