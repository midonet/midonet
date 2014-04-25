/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Runnables {
    private static final Logger log = LoggerFactory.getLogger(Runnables.class);

    public static Runnable NO_OP = new Runnable() {
        @Override
        public void run() { }
    };

    public static abstract class RunOnceRunnable implements Runnable {
        private boolean hasRun = false;
            @Override
            public void run() {
                if (hasRun) {
                    log.error("It is not allowed to run this callback twice)");
                } else {
                    hasRun = true;
                    runOnce();
                }
            }

        protected abstract void runOnce();
    }
}


