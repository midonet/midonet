#!/bin/bash

# Install the latest packages from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# We need to create the vpp init script because the vpp package
# will fail otherwise if the upstart process is not running.
# This is a test specific configuration as the package would
# install under normal circumstances (upstart running).
touch /etc/init.d/vpp

# Failfast if we cannot update the packages locally
apt-get install -qy --force-yes midolman/local \
                                midonet-tools/local || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

# Wait for configured interfaces to be set up
# The name of each additional interface should be provided
# in an env var with the _IFACE suffix.
for IFACE in `env | grep _IFACE | cut -d= -f2 | sort -u`; do
    # TODO: change pipework by native docker networking once stable
    echo "Waiting for interface $IFACE to be up"
    timeout 300s pipework --wait -i $IFACE
    if [ $? -eq 124 ]; then
        echo "Interface $IFACE was not ready after 60s. Exiting..."
        exit 1
    fi
done

# Add vtysh pager for easy debugging
export VTYSH_PAGER=more

# Midonet do not support ipv6
sysctl -w net.ipv6.conf.all.disable_ipv6=1
sysctl -w net.ipv6.conf.default.disable_ipv6=1
sysctl -w net.ipv6.conf.lo.disable_ipv6=1

# Default cassandra replication factor
if [ -z "$CASS_FACTOR" ]; then
    CASS_FACTOR=1
fi

# Default mido_zookeeper_key
if [ -z "$MIDO_ZOOKEEPER_ROOT_KEY" ]; then
    MIDO_ZOOKEEPER_ROOT_KEY=/midonet/v1
fi

# Update ZK hosts in case they were linked to this container
if [[ `env | grep _PORT_2181_TCP_ADDR` ]]; then
    MIDO_ZOOKEEPER_HOSTS="$(env | grep _PORT_2181_TCP_ADDR | sed -e 's/.*_PORT_2181_TCP_ADDR=//g' -e 's/^.*/&:2181/g' | sort -u)"
    MIDO_ZOOKEEPER_HOSTS="$(echo $MIDO_ZOOKEEPER_HOSTS | sed 's/ /,/g')"
fi

# Update CASS hosts in case they were linked to this container
if [[ `env | grep _PORT_9042_TCP_ADDR` ]]; then
    CASS_SERVERS="$(env | grep _PORT_9042_TCP_ADDR | sed 's/.*_PORT_9042_TCP_ADDR=//g' | sed -e :a -e N | sort -u)"
    CASS_SERVERS="$(echo $CASS_SERVERS | sed 's/ /,/g')"
fi

if [ -z "$MIDO_ZOOKEEPER_HOSTS" ]; then
    echo "No Zookeeper hosts specified neither by ENV VAR nor by linked containers"
    exit 1
fi

echo "Configuring agent using MIDO_ZOOKEEPER_HOSTS: $MIDO_ZOOKEEPER_HOSTS"
echo "Configuring agent using MIDO_ZOOKEEPER_ROOT_KEY: $MIDO_ZOOKEEPER_ROOT_KEY"

sed -i -e 's/zookeeper_hosts = .*$/zookeeper_hosts = '"$MIDO_ZOOKEEPER_HOSTS"'/' /etc/midolman/midolman.conf
sed -i -e 's/root_key = .*$/root_key = '"$(echo $MIDO_ZOOKEEPER_ROOT_KEY|sed 's/\//\\\//g')"'/' /etc/midolman/midolman.conf

cat << EOF > /root/.midonetrc
[zookeeper]
zookeeper_hosts = $MIDO_ZOOKEEPER_HOSTS
root_key = $MIDO_ZOOKEEPER_ROOT_KEY
EOF


# Wait for the cluster to update zookeeper to
# instruct agents to use the new v2 stack
echo "Setting up mn-conf..."
mn-conf set -t default <<EOF
zookeeper.zookeeper_hosts="$MIDO_ZOOKEEPER_HOSTS"
cassandra.servers="$CASS_SERVERS"
cassandra.replication_factor=$CASS_FACTOR
cassandra.cluster=midonet
agent.midolman.bgp_keepalive=1s
agent.midolman.bgp_holdtime=3s
agent.midolman.bgp_connect_retry=1s
agent.midolman.lock_memory=false
agent.midolman.simulation_threads=2
agent.loggers.root=DEBUG
agent.haproxy_health_monitor.namespace_cleanup=true
agent.haproxy_health_monitor.health_monitor_enable=true
agent.haproxy_health_monitor.haproxy_file_loc=/etc/midolman/l4lb/
agent.minions.flow_state.legacy_push_state=true
agent.minions.flow_state.local_push_state=false
state_proxy.enabled=false
EOF

# enable debug logs from first boot
sed -i 's/<root level="INFO">/<root level="DEBUG">/' /etc/midolman/logback.xml
sed -i '/<root level="DEBUG">/i \    <logger name="org.apache.zookeeper" level="INFO" />' /etc/midolman/logback.xml
sed -i '/<root level="DEBUG">/i \    <logger name="org.apache.cassandra" level="INFO" />' /etc/midolman/logback.xml
sed -i '/<root level="DEBUG">/i \    <logger name="me.prettyprint.cassandra" level="INFO" />' /etc/midolman/logback.xml
sed -i '/<root level="DEBUG">/i \    <logger name="org.eclipse.jetty" level="INFO" />' /etc/midolman/logback.xml

echo "Starting agent!"
exec /sbin/init
