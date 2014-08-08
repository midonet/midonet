/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import com.yammer.metrics.core.Clock;

public class MockClock extends Clock {
    public long time = 0;

    @Override
    public long tick() {
        return time;
    }
}
