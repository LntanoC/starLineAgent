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

package com.nageoffer.ai.starlineagent.starline.seatview;

import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.DateTimeException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.nageoffer.ai.starlineagent.starline.seatview.SeatViewDocument.*;

public final class SeatViewValidation {
    private SeatViewValidation() {}

    public static void validate(SeatViewDocument document) {
        require(document != null && document.venue() != null && document.show() != null, "缺少场馆或演出");
        Venue venue = document.venue();
        Show show = document.show();
        id(venue.id());
        id(show.id());
        text(venue.name(), 120);
        text(venue.city(), 80);
        require(venue.aliases() != null && venue.aliases().size() <= 30, "场馆别名过多");
        venue.aliases().forEach(alias -> text(alias, 120));
        text(show.name(), 200);
        require(Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(orEmpty(show.status())), "发布状态无效");
        require(Set.of("DEMO", "REVIEWED_SCHEMATIC").contains(orEmpty(show.provenance())), "数据类型无效");
        require(Set.of("END", "CENTER", "T").contains(orEmpty(show.stageLayout())), "舞台布局无效");
        text(show.sourceNote(), 2000);
        try {
            text(show.sourceUrl(), 2000);
            URI url = URI.create(show.sourceUrl());
            require(Set.of("https", "http").contains(orEmpty(url.getScheme())) && url.getHost() != null
                    && url.getUserInfo() == null, "来源须为无凭据的 HTTP/HTTPS 链接");
            if (show.startsAt() != null) OffsetDateTime.parse(show.startsAt());
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new ClientException("来源链接或演出时间格式无效，时间须包含时区");
        }
        Stage stage = show.stage();
        require(stage != null && finite(stage.x()) && finite(stage.y()) && finite(stage.width())
                && finite(stage.height()) && stage.width() > 0 && stage.height() > 0
                && Double.isFinite(stage.rotation()) && Math.abs(stage.rotation()) <= 360, "舞台几何无效");
        require(show.sections() != null && !show.sections().isEmpty() && show.sections().size() <= 300,
                "每场须有 1 到 300 个分区");
        Set<String> codes = new HashSet<>();
        for (Section section : show.sections()) {
            require(section != null, "分区不能为空");
            id(section.code());
            require(codes.add(section.code()), "分区编号重复");
            text(section.label(), 120);
            require(section.level() >= 1 && section.level() <= 3, "楼层须为 1 到 3");
            require(section.priceTier() == null || (section.priceTier() >= 0 && section.priceTier() <= 100000), "票档无效");
            require(section.frontEdge() >= 0 && section.frontEdge() < 4, "朝舞台边编号无效");
            validateRing(section.ring());
        }
    }

    static void validateRing(List<List<Double>> ring) {
        require(ring != null && ring.size() == 4, "P0 分区须为凸四边形；复杂分区请拆成多个稳定编号");
        for (List<Double> point : ring) {
            require(point != null && point.size() == 2 && point.get(0) != null && point.get(1) != null
                    && finite(point.get(0)) && finite(point.get(1)), "分区坐标无效");
        }
        double sign = 0;
        // 只接收严格凸四边形，双线性插值的所有结果才能保证位于分区内。
        for (int i = 0; i < 4; i++) {
            List<Double> a = ring.get(i), b = ring.get((i + 1) % 4), c = ring.get((i + 2) % 4);
            double cross = (b.get(0) - a.get(0)) * (c.get(1) - b.get(1))
                    - (b.get(1) - a.get(1)) * (c.get(0) - b.get(0));
            require(Math.abs(cross) > 0.000001 && (sign == 0 || sign * cross > 0), "分区边界交叉、凹陷或退化");
            sign = cross;
        }
    }

    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 100000; }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static void id(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}"), "编号须为字母、数字及短横线，最多 100 字符");
    }
    private static void text(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max, "文本为空或超长");
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new ClientException(message);
    }
}
