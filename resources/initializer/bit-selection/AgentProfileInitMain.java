/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Creates and activates the BitSelection agent profile; the builtin agent is left untouched. */
public final class AgentProfileInitMain {

    private AgentProfileInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeAgentProfile(context);
            System.out.println("[initializer] SUCCESS");
        });
    }
}
