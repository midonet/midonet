#!/bin/bash

mkdir /usr/local/java
pushd /usr/local/java

wget http://artifactory.bcn.midokura.com/artifactory/midokura-local/jre-7u79-linux-x64.tar.gz
tar -xzf jre-7u79-linux-x64.tar.gz
rm jre-7u79-linux-x64.tar.gz

popd

JRE_HOME=/usr/local/java/jre1.7.0_79
PATH=$PATH:$JRE_HOME/bin

export JRE_HOME
export PATH

sudo update-alternatives --install "/usr/bin/java" "java" "/usr/local/java/jre1.7.0_79/bin/java" 1
sudo update-alternatives --set java /usr/local/java/jre1.7.0_79/bin/java

# Install the latest packages from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# Failfast if we cannot update the packages locally
apt-get install -qy --force-yes midolman/local || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

exec /run-midolman.sh
