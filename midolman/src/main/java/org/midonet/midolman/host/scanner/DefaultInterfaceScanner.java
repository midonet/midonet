/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.host.scanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import org.midonet.Subscription;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.sensor.InterfaceSensor;
import org.midonet.midolman.host.sensor.IpAddrInterfaceSensor;
import org.midonet.midolman.host.sensor.IpTuntapInterfaceSensor;
import org.midonet.midolman.host.sensor.NetlinkInterfaceSensor;
import org.midonet.midolman.host.sensor.SysfsInterfaceSensor;
import org.midonet.netlink.Callback;

/**
 * Default implementation for the interface scanning.
 */
@Singleton
public class DefaultInterfaceScanner implements InterfaceScanner {
    private static final long UPDATE_RATE_MILLIS = 2000L;

    private final Timer timer;
    private final ArrayList<Callback<Set<InterfaceDescription>>> callbacks;
    private final List<InterfaceSensor> sensors = new ArrayList<>();
    private volatile boolean isRunning;
    private Set<InterfaceDescription> lastScan = new HashSet<>();

    @Inject
    public DefaultInterfaceScanner(Injector injector) {
        // Always call first IpAddrInterfaceSensor, as it is the sensor who
        // will create the interfaces
        sensors.add(injector.getInstance(IpAddrInterfaceSensor.class));
        sensors.add(injector.getInstance(IpTuntapInterfaceSensor.class));
        sensors.add(injector.getInstance(SysfsInterfaceSensor.class));
        sensors.add(injector.getInstance(NetlinkInterfaceSensor.class));
        callbacks = new ArrayList<>();
        timer = new Timer("interface-scanner", true);
    }

    public Subscription register(final Callback<Set<InterfaceDescription>> callback) {
        synchronized (callbacks) {
            callbacks.add(callback);
            callback.onSuccess(lastScan);  // This is potentially dangerous.
        }

        return new Subscription() {
            private final AtomicBoolean unsubscribed = new AtomicBoolean();

            @Override
            public boolean isUnsubscribed() {
                return unsubscribed.get();
            }

            @Override
            public void unsubscribe() {
                if (unsubscribed.compareAndSet(false, true)) {
                    synchronized (callbacks) {
                        callbacks.remove(callback);
                    }
                }
            }
        };
    }

    public void start() {
        isRunning = true;
        scheduleScan();
    }

    public void shutdown() {
        isRunning = false;
    }

    private void scheduleScan() {
        if (isRunning) {
            scanInterfaces();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    scheduleScan();
                }
            }, UPDATE_RATE_MILLIS);
        }
    }

    private void scanInterfaces() {
        Set<InterfaceDescription> interfaces = new HashSet<>();

        for (InterfaceSensor sensor : sensors) {
            sensor.updateInterfaceData(interfaces);
        }

        if (lastScan.equals(interfaces))
            return;

        lastScan = interfaces;
        synchronized (callbacks) {
            for (Callback<Set<InterfaceDescription>> cb : callbacks) {
                cb.onSuccess(interfaces);
            }
        }
    }
}
