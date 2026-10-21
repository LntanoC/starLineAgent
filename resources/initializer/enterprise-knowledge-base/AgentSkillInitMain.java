/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

/** Rebuilds agent skills; requires the intent tree to already carry the MCP nodes they unlock. */
public final class AgentSkillInitMain {
    private AgentSkillInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeSkills(context);
        });
    }
}
