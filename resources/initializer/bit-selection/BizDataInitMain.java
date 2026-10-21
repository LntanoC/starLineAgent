/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

/** Reseeds the BitSelection business database with demo products, orders, coupons and cart rows. */
public final class BizDataInitMain {

    private BizDataInitMain() {
    }

    public static void main(String[] args) {
        MainSupport.run(args, context -> {
            InitializationActions.preflight(context);
            InitializationActions.initializeBizData(context);
            System.out.println("[initializer] SUCCESS");
        });
    }
}
