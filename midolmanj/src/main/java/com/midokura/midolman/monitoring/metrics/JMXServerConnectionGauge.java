/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import com.yammer.metrics.core.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/24/12
 */
public class JMXServerConnectionGauge extends Gauge<Object> {

    private final static Logger log =
            LoggerFactory.getLogger(JMXServerConnectionGauge.class);

    private MBeanServerConnection serverConnection;

    private final ObjectName objectName;
    private final String attribute;

    public JMXServerConnectionGauge(String objectName, String attribute, MBeanServerConnection serverConnection)
            throws MalformedObjectNameException {
        this(new ObjectName(objectName), attribute, serverConnection);
    }

    public JMXServerConnectionGauge(ObjectName objectName, String attribute, MBeanServerConnection serverConnection){
        this.objectName = objectName;
        this.attribute = attribute;
        this.serverConnection = serverConnection;
    }

    @Override
    public Object value() {
        try {
            return serverConnection.getAttribute(objectName, attribute);
        } catch (Exception e) {
            log.error("There was a problem in retrieving {}, {}", new Object[]{objectName, attribute, e});
            return null;
        }
    }
}
