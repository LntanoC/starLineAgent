/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Rebuilds the intent tree, including the MCP nodes that back the business tools. */
public final class IntentTreeInitMain {

    private IntentTreeInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeIntentTree(context);
        });
    }
}
