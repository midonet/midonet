/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.gauges;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

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
    private Class<T> type;

    public JMXRemoteBeanGauge(MBeanServerConnection serverConn, Class<T> type,
                              String beanName, String beanAttr)
        throws MalformedObjectNameException {
        this.beanName = new ObjectName(beanName);
        this.beanAttr = beanAttr;
        this.serverConnection = serverConn;
        this.type = type;
    }

    @Override
    public T value() {
        Object val = null;
        try {
            val = serverConnection.getAttribute(beanName,
                                                beanAttr);
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
}
