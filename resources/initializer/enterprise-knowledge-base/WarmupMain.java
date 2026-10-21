/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package com.nageoffer.ai.ragent.initializer;

public final class WarmupMain {
    private WarmupMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.warmup(context);
        });
    }
}
