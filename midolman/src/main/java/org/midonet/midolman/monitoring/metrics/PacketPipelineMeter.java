/*
 * Copyright 2013 Midokura Pte. Ltd.
 */

package org.midonet.midolman.monitoring.metrics;

/**
  * A class name to publish metrics under. It's meant to be used while creating
  * a metrics object from the yammer.metrics library, and will act as a marker
  * to organize the metrics when exported via JMX.
  */
public interface PacketPipelineMeter {}
