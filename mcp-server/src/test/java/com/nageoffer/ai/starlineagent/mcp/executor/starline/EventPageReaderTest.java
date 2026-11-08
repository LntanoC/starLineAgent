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
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class EventPageReaderTest {
    @Test void prefersArtistEventStructuredDatesAndIgnoresOtherArtists() throws Exception {
        String html = """
                <script type="application/ld+json">[
                {"@type":"MusicEvent","name":"薛之谦无锡站","startDate":"2026-11-13T19:00:00+08:00"},
                {"@type":"MusicEvent","name":"另一位歌手","startDate":"2026-11-20"}]
                </script><p>历史公告2025年1月1日</p>
                """;
        String text = new EventPageReader().extract(html, "薛之谦");
        assertTrue(text.contains("2026-11-13"));
        assertFalse(text.contains("2026-11-20"));
        assertFalse(text.contains("2025"));
    }

    @Test void visibleTextDropsScriptsAndRecommendationBlocks() throws Exception {
        String text = new EventPageReader().extract("<p>薛之谦 演出日期2026年12月4日</p><script>2020年1月1日</script><p>相关推荐</p><p>北京2026年11月1日</p>", "薛之谦");
        assertTrue(text.contains("2026年12月4日"));
        assertFalse(text.contains("2020"));
        assertFalse(text.contains("北京"));
    }

    @Test void rejectsPrivateTargetsAndUserInfo() throws Exception {
        assertFalse(EventPageReader.publicUri(URI.create("http://127.0.0.1/a")));
        assertFalse(EventPageReader.publicUri(URI.create("http://[::1]/a")));
        assertFalse(EventPageReader.publicUri(URI.create("http://10.0.0.1/a")));
        assertFalse(EventPageReader.publicUri(URI.create("http://user@tickets.example/a")));
    }

    @Test void excludesSameArtistOtherCityRecommendationsFromStructuredData() throws Exception {
        String html = """
                <script type="application/ld+json">[
                {"@type":"MusicEvent","name":"薛之谦无锡站","startDate":"2026-11-13"},
                {"@type":"MusicEvent","name":"薛之谦赣州站","startDate":"2026-12-04"}]
                </script>
                """;
        String text = new EventPageReader().extract(html, "薛之谦", "无锡");
        assertTrue(text.contains("2026-11-13"));
        assertFalse(text.contains("2026-12-04"));
    }
}
