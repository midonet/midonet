#!/usr/bin/env bash

# Copyright 2015 Midokura SARL
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

# Make sure custom grep options don't get in the way
unset GREP_OPTIONS

# Sanitize language settings to avoid commands bailing out
# with "unsupported locale setting" errors.
unset LANG
unset LANGUAGE
LC_ALL=C
export LC_ALL

# Make sure umask is sane
umask 022

# Not all distros have sbin in PATH for regular users.
PATH=$PATH:/usr/local/sbin:/usr/sbin:/sbin

# Keep track of the current directory
DEVMIDO_DIR=$(cd $(dirname $0) && pwd)

# Keep track of the midonet root directory
TOP_DIR=$(cd $DEVMIDO_DIR/../../ && pwd)

# Check for uninitialized variables, a big cause of bugs
NOUNSET=${NOUNSET:-}
if [[ -n "$NOUNSET" ]]; then
    set -o nounset
fi

# Import common functions
source $DEVMIDO_DIR/functions

# Check if run as root
if [[ $EUID -eq 0 ]]; then
    die $LINENO "You cannot run this script as root."
fi

MIDORC=$DEVMIDO_DIR/midorc
if [[ ! -r $MIDORC ]]; then
    die $LINENO "Missing $MIDORC"
fi
source $MIDORC

# Sanity checks
if [[ $ENABLE_API = "False" && $ENABLE_CLUSTER = "False" ]]; then
    die $LINENO "At least one of ENABLE_API or ENABLE_CLUSTER must be true"
fi

# Configure Logging
TIMESTAMP_FORMAT=${TIMESTAMP_FORMAT:-"%F-%H%M%S"}
if [[ -n "$LOGFILE" || -n "$SCREEN_LOGDIR" ]]; then
    CURRENT_LOG_TIME=${CURRENT_LOG_TIME:-$(date "+$TIMESTAMP_FORMAT")}
fi

# Skip logging setup by setting this to False.  This is useful if you are
# running mido.sh as part of anther script and the logging has already been
# configured
if [[ "$CONFIGURE_LOGGING" = "True" ]]; then
    if [[ -n "$LOGFILE" ]]; then
        LOGFILE_DIR="${LOGFILE%/*}"           # dirname
        LOGFILE_NAME="${LOGFILE##*/}"         # basename
        mkdir -p $LOGFILE_DIR
        LOGFILE=$LOGFILE.${CURRENT_LOG_TIME}

        # Copy stdout to fd 3
        exec 3>&1

        # Set fd 1 and 2 to primary logfile
        exec 1> "${LOGFILE}" 2>&1

        echo "mido.sh log $LOGFILE"

        ln -sf $LOGFILE $LOGFILE_DIR/$LOGFILE_NAME
    else
        # Set up output redirection without log files
        # Copy stdout to fd 3
        exec 3>&1
    fi

fi

# Set up logging of screen windows
if [[ -n "$SCREEN_LOGDIR" ]]; then
    mkdir -p $SCREEN_LOGDIR
fi


# Dump the system information on exit
trap exit_trap EXIT
function exit_trap {
    local r=$?
    if [[ $r -ne 0 ]]; then
        echo "Error on exit"
        echo "File System Summary:"
        df -Ph
        echo "Process Listing:"
        ps auxw
    fi

    exit $r
}

# Exit on any errors so that errors don't compound
trap err_trap ERR
function err_trap {
    local r=$?
    set +o xtrace
    echo "${0##*/} failed"
    exit $r
}

# Begin trapping error exit codes
set -o errexit

# Print the commands being run so that we can see the command that triggers
# an error.  It is also useful for following along as the install occurs.
set -o xtrace


# Configure screen
# ----------------

# Hard code the screen name so that mido.sh and unmido.sh would be in sync
# when creating and deleting screen sessions.
SCREEN_NAME=mido

if [[ "$USE_SCREEN" = "True" ]]; then
    is_package_installed screen || install_package screen


    # Check to see if we are already running mido.sh
    if is_screen_running $SCREEN_NAME ; then
        echo "You are already running a mido.sh session."
        echo "To rejoin this session type 'screen -x $SCREEN_NAME'."
        echo "To destroy this session, type './unmido.sh'."
        exit 1
    fi

    # Create a new named screen to run processes in
    create_screen $SCREEN_NAME

    # Clear screen rc file
    SCREENRC=$DEVMIDO_DIR/$SCREEN_NAME-screenrc
    if [[ -e $SCREENRC ]]; then
        rm -f $SCREENRC
    fi
fi

# OpenvSwitch
# ===========

is_kmod_loaded openvswitch || sudo modprobe openvswitch


# Java and other basic dependencies
# =================================

is_package_installed python-dev || install_package python-dev
is_package_installed build-essential || install_package build-essential
is_package_installed curl || install_package curl
is_package_installed git || install_package git
is_package_installed libreadline-dev || install_package libreadline-dev
is_package_installed ncurses-dev || install_package ncurses-dev
is_package_installed wget || install_package wget
is_package_installed ruby-ronn || install_package ruby-ronn

if ! is_package_installed zulu-8; then
    sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0x219BD9C9
    sudo apt-add-repository "deb http://repos.azulsystems.com/ubuntu stable main"
    REPOS_UPDATED=False install_package zulu-8
fi

# Zookeeper
# =========

is_package_installed zookeeperd || install_package zookeeperd
sudo rm -rf /var/lib/zookeeper/*
sudo service zookeeper restart


# Cassandra
# =========

if ! is_package_installed cassandra ; then
    # Install cassandra
    CASSANDRA_LIST_FILE=/etc/apt/sources.list.d/cassandra.list
    if [ ! -f $CASSANDRA_LIST_FILE ]; then
        echo "deb http://debian.datastax.com/community 2.0 main" | sudo tee $CASSANDRA_LIST_FILE
    fi
    curl -L http://debian.datastax.com/debian/repo_key | sudo apt-key add -
    # Install latest Cassandra 2.0 release (newer versions are not yet supported).
    is_package_installed cassandra || REPOS_UPDATED=False install_package cassandra
    is_package_installed dsc20 || install_package dsc20

    # Initialize/Configure cassandra
    CASSANDRA_LIST_FILE=/etc/apt/sources.list.d/cassandra.list
    CASSANDRA_FILE='/etc/cassandra/cassandra.yaml'
    CASSANDRA_ENV_FILE='/etc/cassandra/cassandra-env.sh'
    sudo service cassandra stop
    sudo chown cassandra:cassandra /var/lib/cassandra
    sudo rm -rf /var/lib/cassandra/data/system/LocationInfo

    sudo sed -i -e "s/^cluster_name:.*$/cluster_name: \'midonet\'/g" $CASSANDRA_FILE
    sudo sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1128M/' $CASSANDRA_ENV_FILE
    sudo sed -i 's/\(HEAP_NEWSIZE=\).*$/\164M/' $CASSANDRA_ENV_FILE
    # Cassandra seems to need at least 228k stack working with Java 7.
    # Related bug: https://issues.apache.org/jira/browse/CASSANDRA-5895
    sudo sed -i -e "s/-Xss180k/-Xss228k/g" $CASSANDRA_ENV_FILE
fi

sudo rm -rf /var/lib/cassandra/*
sudo service cassandra restart


# Protobuf
# ========

if ! which protoc > /dev/null || [ "$(protoc --version | awk '{print $2}')" != "2.6.1" ]; then
    # Currently the package provided for Ubuntu is not new enough
    # Replace with "is_package_installed protobuf-compiler || install_package protobuf-compiler"
    # when that changes.
    wget https://github.com/google/protobuf/releases/download/v2.6.1/protobuf-2.6.1.tar.gz
    tar -xzf protobuf-2.6.1.tar.gz
    cd protobuf-2.6.1
    ./configure
    make
    sudo make install
    sudo ldconfig
    cd -
    rm -rf protobuf-2.6.1
    rm protobuf-2.6.1.tar.gz
fi


# MidoNet
# =======

cd $TOP_DIR

git submodule update --init

# Build midonet
./gradlew clean
./gradlew assemble

# Generate jars for installation for midolman and midonet-tools
./gradlew :midolman:installShadowApp
./gradlew :midonet-tools:installShadowApp

# install the midonet scripts.  This must happen before the cluster
# configuration section since mn-conf is used to configure the cluster.
sudo $DEVMIDO_DIR/install_mn_scripts.sh

# put config to the classpath and set loglevel to DEBUG for Midolman
cp  $TOP_DIR/midolman/src/test/resources/logback-test.xml  \
    $TOP_DIR/midolman/build/classes/main/logback.xml

# MidoNet Cluster
# ---------------

# Copy over the cluster config
mkdir -p $TOP_DIR/conf
CLUSTER_CONF=$TOP_DIR/conf/midonet-cluster.conf
cp midonet-cluster/conf/midonet-cluster.conf $CLUSTER_CONF
iniset ${CLUSTER_CONF} zookeeper zookeeper_hosts $ZOOKEEPER_HOSTS

# Configure the cluster using mn-conf
configure_mn "cluster.rest_api.http_port" $API_PORT
configure_mn "cluster.topology_api.enabled" "true"
configure_mn "cluster.topology_api.port" $TOPOLOGY_API_PORT
if [[ "$ENABLE_TASKS_IMPORTER" = "True" ]]; then
    configure_mn "cluster.neutron_importer.enabled" "true"
    configure_mn "cluster.neutron_importer.connection_string" "\"$TASKS_DB_CONN\""
    configure_mn "cluster.neutron_importer.jdbc_driver_class" "\"$TASKS_DB_DRIVER_CLASS\""
fi

# Configure the embedded metadata proxy
if [[ "$USE_METADATA" = "True" ]]; then
    configure_mn "agent.openstack.metadata.enabled" "true"
    configure_mn "agent.openstack.metadata.nova_metadata_url" \
        "$NOVA_METADATA_URL"
    configure_mn "agent.openstack.metadata.shared_secret" \
        "$METADATA_SHARED_SECRET"
fi

CLUSTER_LOG=$TOP_DIR/midonet-cluster/conf/logback.xml
cp $CLUSTER_LOG.dev $TOP_DIR/midonet-cluster/build/resources/main/logback.xml

run_process midonet-cluster "./gradlew :midonet-cluster:run"

if ! timeout $API_TIMEOUT sh -c "while ! wget -q -O- $API_URI; do sleep 1; done"; then
    die $LINENO "API server didn't start in $API_TIMEOUT seconds"
fi

# Midolman
# --------

configure_mn "agent.loggers.root" "DEBUG"
run_process midolman "./gradlew -a :midolman:runWithSudo"

# MidoNet Client
# --------------

sudo pip install -U webob readline httplib2 protobuf
cd $TOP_DIR/python-midonetclient
sudo python setup.py build_py
sudo python setup.py develop

# Make sure to remove system lib path in case it exists
PYTHON_PACKAGE_DIR=/usr/lib/python2.7/dist-packages
EASY_INSTALL_FILE=/usr/local/lib/python2.7/dist-packages/easy-install.pth
if grep -qw $PYTHON_PACKAGE_DIR $EASY_INSTALL_FILE; then
    grep -v $PYTHON_PACKAGE_DIR $EASY_INSTALL_FILE | sudo tee $EASY_INSTALL_FILE
fi

# Wait for HostService to register the host
if ! timeout 60 sh -c 'while test -z $(midonet-cli -e host list); do sleep 1; done'; then
    die $LINENO "HostService didn't register the host"
fi

echo "devmido has successfully completed in $SECONDS seconds."
