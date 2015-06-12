#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST_CLIENT=$(ls override/python-midonetclient*deb | tail -n1)
LATEST_CLUSTER=$(ls override/midonet-cluster*deb | tail -n1)
dpkg -i --force-confnew $LATEST_CLIENT $LATEST_CLUSTER

# Remove the midonet-api and tomcat packages as we no longer need it for cluster
apt-get -qy remove tomcat7 midonet-api

# Update ZK hosts in case they were linked to this container
if [[ `env | grep _PORT_2181_TCP_ADDR` ]]; then
    ZK_HOSTS="$(env | grep _PORT_2181_TCP_ADDR | sed -e 's/.*_PORT_2181_TCP_ADDR=//g' -e 's/^.*/&:2181/g' | sort -u)"
    ZK_HOSTS="$(echo $ZK_HOSTS | sed 's/ /,/g')"
fi

ZK_ROOT_KEY=/midonet/v1

IP=${LISTEN_ADDRESS:-`hostname --ip-address`}

if [ -z "$ZK_HOSTS" ]; then
    echo "No Zookeeper hosts specified neither by ENV VAR nor by linked containers"
    exit 1
fi

# Edit web.xml
cp /etc/midonet-cluster/logback.xml.dev /etc/midonet-cluster/logback.xml

# Update config file to point to ZK
sed -i -e 's/zookeeper_hosts = .*$/zookeeper_hosts = '"$ZK_HOSTS"'/' /etc/midonet-cluster/midonet-cluster.conf
sed -i -e 's/root_key = .*$/root_key = '"$(echo $ZK_ROOT_KEY|sed 's/\//\\\//g')"'/' /etc/midonet-cluster/midonet-cluster.conf

# Update midonet-cli to point to cluster
sed -i 's/api_url = .*$/api_url = http:\/\/localhost:8181\/midonet-api/' /root/.midonetrc

# Run cluster
exec /sbin/init
