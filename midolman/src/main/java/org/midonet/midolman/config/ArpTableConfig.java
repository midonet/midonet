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
