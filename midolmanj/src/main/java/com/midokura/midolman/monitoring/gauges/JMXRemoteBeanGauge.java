/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.gauges;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.yammer.metrics.core.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Date: 4/24/12
 */
public class JMXRemoteBeanGauge<T> extends Gauge<T> {

    private final static Logger log =
        LoggerFactory.getLogger(JMXRemoteBeanGauge.class);

    private MBeanServerConnection serverConnection;
    private ObjectName beanName;
    private String beanAttr;
    private String compositeKeyName;
    private Class<T> type;

    public JMXRemoteBeanGauge(MBeanServerConnection serverConn, Class<T> type,
                              String beanName, String beanAttr)
        throws MalformedObjectNameException {
        this(serverConn, type, beanName, beanAttr, null);
    }

    public JMXRemoteBeanGauge(MBeanServerConnection serverConn,
                                    Class<T> type,
                                    String beanName, String beanAttr,
                                    String compositeKeyName)
        throws MalformedObjectNameException {
        this.beanName = new ObjectName(beanName);
        this.beanAttr = beanAttr;
        this.compositeKeyName = compositeKeyName;
        this.serverConnection = serverConn;
        this.type = type;

    }

    @Override
    public T value() {
        Object val = null;
        try {
            val = serverConnection.getAttribute(beanName,
                                                beanAttr);

            if (compositeKeyName != null) {
                return getValueFromCompositeType(val, compositeKeyName, type);
            }

            return type.cast(val);
        } catch (ClassCastException e) {
            log.error("Couldn't convert class {} got from {}, {} to {}",
                      new Object[]{
                          val.getClass().getCanonicalName(),
                          beanAttr, type.getCanonicalName(), e});
        } catch (Exception e) {
            log.error("There was a problem in retrieving {}, {}",
                      new Object[]{beanName, beanAttr, e});
        }
        return null;
    }

    private T getValueFromCompositeType(Object val, String compositeKeyName,
                                        Class<T> type) {
        if (!(val instanceof CompositeData))
            return null;

        CompositeData compositeData = (CompositeData) val;

        return type.cast(compositeData.get(compositeKeyName));
    }
}
