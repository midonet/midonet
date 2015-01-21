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
MIDO_TOP_DIR=$(cd $DEVMIDO_DIR/../../ && pwd)

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

# Configure Logging
TIMESTAMP_FORMAT=${TIMESTAMP_FORMAT:-"%F-%H%M%S"}
if [[ -n "$LOGFILE" || -n "$SCREEN_LOGDIR" ]]; then
    CURRENT_LOG_TIME=${CURRENT_LOG_TIME:-$(date "+$TIMESTAMP_FORMAT")}
fi

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
    # Specified logfile name always links to the most recent log
    ln -sf $LOGFILE $LOGFILE_DIR/$LOGFILE_NAME
else
    # Set up output redirection without log files
    # Copy stdout to fd 3
    exec 3>&1
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

# Set up logging of screen windows
if [[ -n "$SCREEN_LOGDIR" ]]; then
    mkdir -p $SCREEN_LOGDIR
fi

# Begin trapping error exit codes
set -o errexit

# Print the commands being run so that we can see the command that triggers
# an error.  It is also useful for following along as the install occurs.
set -o xtrace


# Configure screen
# ----------------

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

# OpenvSwitch
# ===========

is_kmod_loaded openvswitch || sudo modprobe openvswitch


# Java and other basic dependencies
# =================================

is_package_installed curl || install_package curl
is_package_installed git || install_package git
is_package_installed libreadline-dev || install_package libreadline-dev
is_package_installed ncurses-dev || install_package ncurses-dev
is_package_installed openjdk-7-jdk || install_package openjdk-7-jdk
is_package_installed python-pip || install_package python-pip
is_package_installed wget || install_package wget


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
        echo "deb http://debian.datastax.com/community stable main" | sudo tee $CASSANDRA_LIST_FILE
    fi
    curl -L http://debian.datastax.com/debian/repo_key | sudo apt-key add -
    is_package_installed dsc21 || REPOS_UPDATED=False install_package dsc21

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

cd $MIDO_TOP_DIR

git submodule update --init

# Build midonet
./gradlew clean
./gradlew assemble


# Midolman
# --------

# install jar to midolman's build dir
./gradlew :midolman:installApp

# Change MIDO_HOME (used by mm-ctl / mm-dpctl) to point at deps dir
MIDO_DEPS_DIR="$MIDO_TOP_DIR/midodeps"
MIDOLMAN_BUILD_TARGET_DIR="$MIDO_TOP_DIR/midolman/build/install/midolman/lib"

rm -rf $MIDO_DEPS_DIR ; mkdir -p $MIDO_DEPS_DIR
cp $MIDOLMAN_BUILD_TARGET_DIR/midolman-*.jar  $MIDO_DEPS_DIR/midolman.jar
cp $MIDOLMAN_BUILD_TARGET_DIR/midonet-jdk-bootstrap-*.jar $MIDO_DEPS_DIR/midonet-jdk-bootstrap.jar
cp -r $MIDOLMAN_BUILD_TARGET_DIR $MIDO_DEPS_DIR/dep

# Place our executables in /usr/local/bin
sed -e "s@{MIDO_HOME}@$MIDO_DEPS_DIR@" \
    -e "s@{MIDO_TOP_DIR}@$MIDO_TOP_DIR@" \
    $DEVMIDO_DIR/binproxy | sudo tee /usr/local/bin/mm-dpctl /usr/local/bin/mm-ctl

# Create the midolman's conf dir in case it doesn't exist
if [ ! -d $MIDOLMAN_CONF_DIR ]; then
    sudo mkdir -p $MIDOLMAN_CONF_DIR
fi

# These config files are needed - create if not present
if [ ! -f $MIDOLMAN_CONF_DIR/logback-dpctl.xml ]; then
    sudo cp $MIDO_TOP_DIR/midolman/conf/logback-dpctl.xml $MIDOLMAN_CONF_DIR/
fi
if [ ! -f $MIDOLMAN_CONF_DIR/midolman.conf ]; then
    sudo cp $MIDO_TOP_DIR/midolman/conf/midolman.conf $MIDOLMAN_CONF_DIR/
fi

# put config to the classpath and set loglevel to DEBUG for Midolman
sed -e 's/"INFO"/"DEBUG"/'  \
    $MIDO_TOP_DIR/midolman/conf/midolman-akka.conf > \
    $MIDO_TOP_DIR/midolman/build/classes/main/application.conf
cp  $MIDO_TOP_DIR/midolman/src/test/resources/logback-test.xml  \
    $MIDO_TOP_DIR/midolman/build/classes/main/logback.xml

screen_process midolman "cd $MIDO_TOP_DIR && ./gradlew -a :midolman:runWithSudo"


# MidoNet API
# -----------

# TODO (ryu) make it work with keystone
MIDO_API_CFG=$MIDO_TOP_DIR/midonet-api/src/main/webapp/WEB-INF/web.xml
cp $MIDO_API_CFG.dev $MIDO_API_CFG

# put logback.xml to the classpath with "debug" level
sed -e 's/info/debug/' \
    -e 's,</configuration>,\
<logger name="org.apache.zookeeper" level="INFO" />\
<logger name="org.apache.cassandra" level="INFO" />\
<logger name="me.prettyprint.cassandra" level="INFO" />\
</configuration>,' \
   $MIDO_TOP_DIR/midonet-api/conf/logback.xml.sample > \
   $MIDO_TOP_DIR/midonet-api/build/classes/main/logback.xml

screen_process midonet-api "cd $MIDO_TOP_DIR && ./gradlew :midonet-api:jettyRun -Pport=$MIDO_API_PORT"

if ! timeout $MIDO_API_TIMEOUT sh -c "while ! wget -q -O- $MIDO_API_URI; do sleep 1; done"; then
    die $LINENO "API server didn't start in $MIDO_API_TIMEOUT seconds"
fi


# MidoNet Client
# --------------

sudo pip install -U webob readline httplib2
cd $MIDO_TOP_DIR/python-midonetclient
sudo python setup.py develop

# Make sure to remove system lib path in case it exists
if grep -qw /usr/lib/python2.7/dist-packages /usr/local/lib/python2.7/dist-packages/easy-install.pth; then
    grep -v /usr/lib/python2.7/dist-packages /usr/local/lib/python2.7/dist-packages/easy-install.pth | sudo tee /usr/local/lib/python2.7/dist-packages/easy-install.pth
fi

echo "devmido has successfully completed in $SECONDS seconds."
