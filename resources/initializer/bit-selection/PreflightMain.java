/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Checks the RagentAI login, backend types, PostgreSQL, Redis, the business database and the idle state. */
public final class PreflightMain {

    private PreflightMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, InitializationActions::preflight);
    }
}
