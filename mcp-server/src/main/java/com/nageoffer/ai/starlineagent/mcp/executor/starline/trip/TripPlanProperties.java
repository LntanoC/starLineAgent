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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "starlineagent.trip-planning")
public class TripPlanProperties {

    private int railStationBufferMinutes = 30;
    private int crowdBufferMinutes = 20;
    private int maxConcurrentRequests = 4;
    private java.math.BigDecimal litresPer100km = new java.math.BigDecimal("8");
    private java.math.BigDecimal yuanPerLitre = new java.math.BigDecimal("8");

    public int getRailStationBufferMinutes() { return railStationBufferMinutes; }
    public void setRailStationBufferMinutes(int value) { railStationBufferMinutes = value; }
    public int getCrowdBufferMinutes() { return crowdBufferMinutes; }
    public void setCrowdBufferMinutes(int value) { crowdBufferMinutes = value; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = value; }
    public java.math.BigDecimal getLitresPer100km() { return litresPer100km; }
    public void setLitresPer100km(java.math.BigDecimal value) { litresPer100km = value; }
    public java.math.BigDecimal getYuanPerLitre() { return yuanPerLitre; }
    public void setYuanPerLitre(java.math.BigDecimal value) { yuanPerLitre = value; }

    private int defaultArrivalBufferMinutes = 30;

    public int getDefaultArrivalBufferMinutes() {
        return defaultArrivalBufferMinutes;
    }

    public void setDefaultArrivalBufferMinutes(int defaultArrivalBufferMinutes) {
        this.defaultArrivalBufferMinutes = defaultArrivalBufferMinutes;
    }
}
