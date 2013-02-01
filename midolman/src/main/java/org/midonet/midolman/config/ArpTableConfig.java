/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman.config;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;

@ConfigGroup(ArpTableConfig.GROUP_NAME)
public interface ArpTableConfig {

    public final static String GROUP_NAME = "arptable";

    @ConfigInt(key = "arp_retry_interval_seconds", defaultValue = 10)
    public int getArpRetryIntervalSeconds();

    @ConfigInt(key = "arp_timeout_seconds", defaultValue = 60)
    public int getArpTimeoutSeconds();

    @ConfigInt(key = "arp_stale_seconds", defaultValue = 1800)
    public int getArpStaleSeconds();

    @ConfigInt(key = "arp_expiration_seconds", defaultValue = 3600)
    public int getArpExpirationSeconds();
}
