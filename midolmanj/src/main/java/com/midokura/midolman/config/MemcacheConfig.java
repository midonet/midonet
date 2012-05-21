/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
@ConfigGroup(MemcacheConfig.GROUP_NAME)
public interface MemcacheConfig {

    String GROUP_NAME = "memcache";

    @ConfigString(key = "memcache_hosts", defaultValue = "127.0.0.1:11211")
    public String getMemcacheHosts();

}
