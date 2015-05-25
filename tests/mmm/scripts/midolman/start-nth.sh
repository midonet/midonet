#!/bin/sh

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

find /var/log/quagga -type f -exec rm -f '{}' ';'

sysctl -w net.ipv6.conf.default.disable_ipv6=1

# If the DEPRECATED tag is in the midolman.conf, this indicates we should
# use the new method of configuration, using mn-conf (not conf files)
if grep -q "DEPRECATED" /etc/midolman/midolman.conf; then

    if test x"$ZOOKEEPER_STANDALONE" = xyes; then
        ZK_HOSTS="10.0.0.2:2181"
        CASS_SERVERS="10.0.0.5"
        CASS_FACTOR=1
    else
        ZK_HOSTS="10.0.0.2:2181,10.0.0.3:2181,10.0.0.4:2181"
        CASS_SERVERS="10.0.0.5,10.0.0.6,10.0.0.7"
        CASS_FACTOR=3
    fi

    export MIDO_ZOOKEEPER_HOSTS=$ZK_HOSTS
    export MIDO_ZOOKEEPER_ROOT_KEY=/midonet/v1

    echo "zookeeper.zookeeper_hosts=\"$ZK_HOSTS\"" | mn-conf set -t default
    echo "cassandra.servers=\"$CASS_SERVERS\"" | mn-conf set -t default
    echo "cassandra.replication_factor=$CASS_FACTOR" | mn-conf set -t default
    echo "cassandra.cluster=midonet" | mn-conf set -t default
    echo "agent.midolman.bgp_keepalive=1s" | mn-conf set -t default
    echo "agent.midolman.bgp_holdtime=3s" | mn-conf set -t default
    echo "agent.midolman.bgp_connect_retry=1s" | mn-conf set -t default
    echo "agent.loggers.root=DEBUG" | mn-conf set -t default
    echo "agent.haproxy_health_monitor.namespace_cleanup=true" | mn-conf set -t default
    echo "agent.haproxy_health_monitor.health_monitor_enable=true" | mn-conf set -t default
    echo "agent.haproxy_health_monitor.haproxy_file_loc=/etc/midolman.1/l4lb/" | mn-conf set -t default
fi

exec /usr/share/midolman/midolman-start
