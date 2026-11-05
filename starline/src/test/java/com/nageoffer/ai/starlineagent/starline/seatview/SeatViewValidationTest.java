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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static com.nageoffer.ai.starlineagent.starline.seatview.SeatViewDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class SeatViewValidationTest {
    private final Section section = new Section("a", "A区", 2, 880,
            List.of(List.of(0., 0.), List.of(10., 0.), List.of(9., 10.), List.of(1., 10.)), 0);

    private SeatViewDocument document(String status, String url, String startsAt, List<Section> sections) {
        return new SeatViewDocument(new Venue("test-venue", "测试馆", "北京", List.of("测试别名")),
                new Show("test-show", "测试场次", startsAt, status, "REVIEWED_SCHEMATIC", url,
                        "人工校对的示意分区", "END", new Stage(5, -5, 3, 2, 0), sections));
    }

    @Test void acceptsAllLifecycleStatesAndBothRingOrientations() {
        for (String status : List.of("DRAFT", "PUBLISHED", "ARCHIVED")) {
            assertDoesNotThrow(() -> SeatViewValidation.validate(document(status, "https://example.com/chart", "2026-11-04T19:00:00+08:00", List.of(section))));
        }
        var reversed = new java.util.ArrayList<>(section.ring());
        java.util.Collections.reverse(reversed);
        assertDoesNotThrow(() -> SeatViewValidation.validateRing(reversed));
    }

    @Test void rejectsConcaveCrossedDegenerateAndNonFiniteGeometry() {
        for (var ring : List.of(
                List.of(List.of(0., 0.), List.of(10., 10.), List.of(0., 10.), List.of(10., 0.)),
                List.of(List.of(0., 0.), List.of(10., 0.), List.of(2., 2.), List.of(0., 10.)),
                List.of(List.of(0., 0.), List.of(5., 0.), List.of(10., 0.), List.of(0., 10.)),
                List.of(List.of(Double.NaN, 0.), List.of(10., 0.), List.of(10., 10.), List.of(0., 10.)))) {
            assertThrows(ClientException.class, () -> SeatViewValidation.validateRing(ring));
        }
    }

    @Test void rejectsUnsafeLinksMissingTimezoneDuplicateSectionsAndUnknownStatus() {
        for (String url : List.of("javascript:alert(1)", "https://user:secret@example.com/chart", "file:///tmp/chart")) {
            assertThrows(ClientException.class, () -> SeatViewValidation.validate(document("PUBLISHED", url, null, List.of(section))));
        }
        assertThrows(ClientException.class, () -> SeatViewValidation.validate(document("PUBLISHED", "https://example.com", "2026-11-04T19:00:00", List.of(section))));
        assertThrows(ClientException.class, () -> SeatViewValidation.validate(document("PUBLISHED", "https://example.com", null, List.of(section, section))));
        assertThrows(ClientException.class, () -> SeatViewValidation.validate(document("UNKNOWN", "https://example.com", null, List.of(section))));
    }

    @Test void validatesEveryBundledDemo() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        List<SeatViewDocument> documents = new ObjectMapper().readValue(root.resolve("resources/seat-view/demo-catalog.json").toFile(), new TypeReference<>() {});
        assertEquals(5, documents.size());
        for (SeatViewDocument document : documents) {
            assertEquals("DEMO", document.show().provenance());
            assertNull(document.show().startsAt());
            assertDoesNotThrow(() -> SeatViewValidation.validate(document), document.venue().name());
        }
    }
}
