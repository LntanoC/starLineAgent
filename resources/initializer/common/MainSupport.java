/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Common lifecycle and exit handling for every Main entry point. */
final class MainSupport {

    private MainSupport() {
    }

    static void run(String[] args, InitializerAction action) {
        try (InitializerContext context = InitializerContext.load(args)) {
            action.execute(context);
        } catch (Exception ex) {
            System.err.println("[initializer] FAILED: " + ex.getMessage());
            if (Boolean.parseBoolean(System.getenv().getOrDefault("RAGENT_INITIALIZER_DEBUG", "false"))) {
                ex.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    @FunctionalInterface
    interface InitializerAction {
        void execute(InitializerContext context) throws Exception;
    }
}
