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

package com.nageoffer.ai.starlineagent.mcp.executor.starline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EventFactExtractorTest {
    @Test void explicitLabelsProduceFactsButSaleAndDateOnlyDoNot() {
        var actual = EventFactExtractor.extract("演出时间：2030年11月21日19:30，演出场馆：测试体育馆，场馆地址：测试路1号，票价：380/680/880元", "TICKETING", "https://example.com/ticket");
        assertEquals("测试体育馆", actual.venue());
        assertEquals("2030-11-21T19:30", actual.start().toString());
        assertEquals(38000, actual.price().minMinor());
        assertEquals(88000, actual.price().maxMinor());
        var missing = EventFactExtractor.extract("开票时间：2030-11-21 10:00，演出日期：2030-11-25", "TICKETING", "https://example.com");
        assertNull(missing.start()); assertNull(missing.venue()); assertNull(missing.price().minMinor());
    }
    @Test void governmentPricesAndStartingAtPriceAreNotQuotedAsFullRanges() {
        assertNull(EventFactExtractor.extract("票价：380/680", "GOV", "https://example.com").price().minMinor());
        assertNull(EventFactExtractor.extract("票价：380元起", "TICKETING", "https://example.com").price().minMinor());
    }
}
