/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

@ConfigGroup(HealthMonitorConfig.GROUP_NAME)
public interface HealthMonitorConfig {

    public final static String GROUP_NAME = "haproxy_health_monitor";

    @ConfigBool(key = "health_monitor_enable", defaultValue = false)
    public boolean getHealthMonitorEnable();

    @ConfigBool(key = "namespace_cleanup", defaultValue = false)
    public boolean getNamespaceCleanup();

    @ConfigString(key = "namespace_suffix", defaultValue = "_MN")
    public String getNamespaceSuffix();

    @ConfigString(key = "haproxy_file_loc",
                  defaultValue = "/etc/midolman/l4lb/")
    public String getHaproxyFileLoc();
}
