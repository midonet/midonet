package com.midokura.midolman;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public interface Reactor {
    
    Future submit(Runnable runnable);

    ScheduledFuture schedule(Runnable runnable, long delay, TimeUnit unit);

}
