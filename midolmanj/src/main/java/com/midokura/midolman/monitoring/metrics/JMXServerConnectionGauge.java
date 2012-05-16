/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.yammer.metrics.core.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/24/12
 */
public class JMXServerConnectionGauge extends Gauge<Object> {

    private final static Logger log =
            LoggerFactory.getLogger(JMXServerConnectionGauge.class);
    private MBeanServerConnection serverConnection;

    private ObjectName objectName;
    private String mBeanAttribute;


    public JMXServerConnectionGauge(MBeanServerConnection serverConnection,
                                    String mBean,
                                    String mBeanAttribute)
            throws MalformedObjectNameException {
        this.objectName = new ObjectName(mBean);
        this.serverConnection = serverConnection;
        this.mBeanAttribute = mBeanAttribute;
    }

    @Override
    public Object value() {
        try {
            return serverConnection.getAttribute(objectName,
                                                 mBeanAttribute);
        } catch (Exception e) {
            log.error("There was a problem in retrieving {}, {}",
                      new Object[]{objectName, mBeanAttribute, e});
            return null;
        }
    }

}
