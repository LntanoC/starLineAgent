/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.starlineagent.initializer;

/** Configures only the MCP tool registry from the reviewed local manifest. */
public final class McpToolInitMain {

    private McpToolInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeMcpTools(context);
        });
    }
}
