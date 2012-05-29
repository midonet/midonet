/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

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

    private ObjectName objectName;
    private String mBeanAttribute;
    Class<T> clazz;


    public JMXRemoteBeanGauge(MBeanServerConnection serverConnection,
                              String mBean,
                              String mBeanAttribute, Class<T> clazz)
            throws MalformedObjectNameException {
        this.objectName = new ObjectName(mBean);
        this.serverConnection = serverConnection;
        this.mBeanAttribute = mBeanAttribute;
        this.clazz = clazz;
    }

    @Override
    public T value() {
        Object val = null;
        try {
            val = serverConnection.getAttribute(objectName,
                                                mBeanAttribute);
            return clazz.cast(val);
        } catch (ClassCastException e) {
            log.error("Couldn't convert class {} got from {}, {} to {}",
                      new Object[]{val.getClass()
                                      .getCanonicalName(), mBeanAttribute, clazz
                              .getCanonicalName(), e});
        } catch (Exception e) {
            log.error("There was a problem in retrieving {}, {}",
                      new Object[]{objectName, mBeanAttribute, e});
        }
        return null;
    }

}
