/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.starlineagent.initializer;

/** Rebuilds agent skills; referenced MCP tools must exist in the registry first. */
public final class AgentSkillInitMain {

    private AgentSkillInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeMcpTools(context);
            InitializationActions.initializeSkills(context);
        });
    }
}
