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

import java.util.List;

/** P0 以演出为单位发布完整快照，避免用户读到半张正在校对的分区图。 */
public record SeatViewDocument(Venue venue, Show show) {
    public record Venue(String id, String name, String city, List<String> aliases) {}

    public record Show(String id, String name, String startsAt, String status, String provenance,
                       String sourceUrl, String sourceNote, String stageLayout, Stage stage,
                       List<Section> sections) {}

    public record Stage(double x, double y, double width, double height, double rotation) {}

    /** ring 按边界顺序存四个点；frontEdge 指定朝舞台的边，0/100 表示相对位置而非排号。 */
    public record Section(String code, String label, int level, Integer priceTier,
                          List<List<Double>> ring, int frontEdge) {}
}
