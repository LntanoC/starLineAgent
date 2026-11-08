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
import com.nageoffer.ai.starlineagent.mcp.executor.starline.event.*;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventTextParserTest {
    @Test void expandsExplicitThreeConsecutiveShows() {
        var result = EventTextParser.dates("薛之谦赣州站\n演出时间：2026年12月4日—6日，连开3场");
        assertEquals(List.of(LocalDate.of(2026,12,4), LocalDate.of(2026,12,5), LocalDate.of(2026,12,6)), result.values());
        assertFalse(result.unresolved());
    }

    @Test void handlesDateListsAndExplicitCrossYear() {
        assertEquals(List.of(LocalDate.of(2026,12,19),LocalDate.of(2026,12,20),LocalDate.of(2026,12,25)),
                EventTextParser.dates("演出日期：2026年12月19日、20日、25日").values());
        assertEquals(List.of(LocalDate.of(2026,12,31),LocalDate.of(2027,1,1)),
                EventTextParser.dates("演出日期：2026年12月31日、2027年1月1日").values());
    }

    @Test void doesNotInventDailyShowsFromTourSpanOrYearFromToday() {
        var result = EventTextParser.dates("薛之谦无锡站 2026.11.13-11.29，共9场");
        assertTrue(result.values().isEmpty());
        assertTrue(result.unresolved());
        assertTrue(EventTextParser.dates("演出日期12月4日、5日").values().isEmpty());
    }

    @Test void distinguishesSalePublicationAndEventDates() {
        var result = EventTextParser.dates("公示时间2026年10月1日；2026年11月9日17:17开票；演出时间2026年11月27日～29日，共3场");
        assertEquals(List.of(LocalDate.of(2026,11,27),LocalDate.of(2026,11,28),LocalDate.of(2026,11,29)), result.values());
    }

    @Test void rejectsInvalidDatesWithoutNormalizingThem() {
        assertTrue(EventTextParser.dates("演出日期2026年2月30日").values().isEmpty());
    }

    @Test void parsesWeekdayDecoratedAndMultipleCountedRanges() {
        assertEquals(3, EventTextParser.dates("演出日期2026年12月4日（周五）—12月6日（周日），连开3场").values().size());
        var result = EventTextParser.dates("演出日期2026年11月13日至15日、20日至22日，共6场");
        assertEquals(6, result.values().size());
        assertFalse(result.unresolved());
    }
}
