#!/bin/bash

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This file contains a test to validate basic isolating between vports.
#
# The test will send traffic at a very high rate from FLOOD_SOURCE_HOST to
# FLOOD_DEST_HOST, and at the same time, will send traffic at a normal rate
# from VICTIM_SOURCE_HOST to VICTIM_DEST_HOST.
#
# To use:
# - Exec ./isolationtest.sh
# 
# Setup:
#
# We employ a simple L2 topology with a chain rule ensuring we see the src
# and dst ports. We establish a baseline by sending traffic at different rates 
# from a source namespace and use cashew for measurements on a receiving 
# namespace. Afterwards, we repeat the test while flooding a different port, 
# which we do by sending traffic at a high rate from the same source namespace, 
# through a different veth pair, to a different receiving namespace. For each
# load we measure latency and packet loss. The results are a set of graphs
# where we plot packet loss by load and different latency percentiles also by 
# load. Each graphs show the baseline measurement as well as the measurement 
# under a flood.

BASEDIR=/var/lib/midonet-isolationtests
TMPDIR=/tmp/midonet-isolationtests
LOGFILE=$TMPDIR/isolationtest.log
REPORT_DIR=

API_URL="localhost:8080/midonet-api/"

LOADGEN=./loadgen.sh

MIDOLMAN_PID=

TSTAMP=
TEST_ID=

MIDONET_SRC_DIR=../..

DURATION_SECS=60
FLOOD_PPS=40000
BASE_PPS=10000
INCREMENT_PPS=5000
TOP_PPS=45000

FLOOD_SOURCE_HOST="172.16.1.1"
FLOOD_DEST_HOST="172.16.1.2"
FLOOD_SOURCE_NET="172.16.1.1/24"
FLOOD_SOURCE_MAC="aa:bb:cc:00:00:11"
FLOOD_DEST_NET="172.16.1.2/24"
FLOOD_DEST_MAC="aa:bb:cc:00:00:22"

VICTIM_SOURCE_HOST="172.16.1.3"
VICTIM_DEST_HOST="172.16.1.4"
VICTIM_SOURCE_NET="172.16.1.3/24"
VICTIM_SOURCE_MAC="aa:bb:cc:00:00:33"
VICTIM_DEST_NET="172.16.1.4/24"
VICTIM_DEST_MAC="aa:bb:cc:00:00:44"

BR_ID=
HOST_ID=

SOURCE_NETNS="source"

FLOOD_DEST_NETNS="flood-dest"
FLOOD_SOURCE_DEV="flood-sourcens"
FLOOD_DEST_DEV="flood-destns"
FLOOD_SOURCE_BINDING="flood-sourcedp"
FLOOD_DEST_BINDING="flood-destdp"

VICTIM_DEST_NETNS="victim-dest"
VICTIM_SOURCE_DEV="victim-sourcens"
VICTIM_DEST_DEV="victim-destns"
VICTIM_SOURCE_BINDING="victim-sourcedp"
VICTIM_DEST_BINDING="victim-destdp"

TOMCAT7_CONF_DIR=/etc/tomcat7/Catalina/localhost/

#######################################################################
# Utility functions
#######################################################################

err_exit() {
    msg=$1
    echo ""
    echo "FATAL: $msg"
    echo ""
    echo "Tests aborted"
    do_cleanup
    exit 1
}

test_phase() {
    msg=$1
    echo ""
    echo "TEST PHASE: $msg"
    echo ""
}

#######################################################################
# Top level functions to control test lifecycle and execution
#######################################################################

reset_logs() {
    rm -f /var/log/midolman/midolman.log
}

do_cleanup() {
    test_phase "Cleaning up"
    destroy_scenario
    stop_midolman
    stop_service tomcat7
    stop_service zookeeper
    rm -rf /var/lib/zookeeper/data
}

assert_dependencies() {
    which vconfig || err_exit "vconfig not installed (apt-get install vlan)"
    which midonet-cli || err_exit "midonet-cli not installed"
    which gnuplot || err_exit "gnuplot not installed"
    test -f $MIDONET_SRC_DIR/midolman/build.gradle || err_exit "directory $MIDONET_SRC_DIR not a midonet code checkout"
    test -f $HOME/.midonetrc || err_exit ".midonetrc not found in $HOME"
}

setup_tests() {
    assert_dependencies
    umask 0022
    mkdir -p $TMPDIR

    start_logging
    gather_build_info
    do_cleanup
    start_service zookeeper
    start_service tomcat7
    reset_logs

    install_midonet_api
    install_midolman

    create_scenario
    connectivity_check
}

start_logging() {
    logpipe=$TMPDIR/$$.logpipe.tmp
    trap "rm -f $logpipe" EXIT
    mknod $logpipe p
    tee <$logpipe $LOGFILE &
    exec 1>&-
    exec 1>$logpipe
    exec 2>&1
}

gather_build_info() {
    HOST=`hostname`
    ABBREV_GITREV=`git log -1 --pretty=format:%h`
    [ $? -eq 0 ] || err_exit "running git log"

    TSTAMP=`date +%Y%m%d-%H%M%S`
    TEST_ID="$HOST-$TSTAMP-$ABBREV_GITREV"
    REPORT_DIR="$BASEDIR/$TEST_ID"
    mkdir -p "$REPORT_DIR"
}

build_report() {
    cp isolation.html $REPORT_DIR/isolation.html
    pushd $REPORT_DIR/..
    tar czf $TEST_ID.tar.gz $TEST_ID
    rm -rf $REPORT_DIR
    popd
}

#######################################################################
# Package installation and services setup
#######################################################################

install_config_file() {
    if [ -z "$1" ] || [ -z "$2" ] ; then
        err_exit "Usage: install_config_file srcfile destpath"
    fi
    src=$1
    destdir=$2

    echo "installing file: isolation/$src to $destdir"
    cp profiles.d/isolation/$src $destdir
}

stop_midolman() {
    dpkg -s midolman > /dev/null 2>&1 || return 1
    status midolman | grep stop/waiting >/dev/null || stop midolman
    status midolman | grep stop/waiting >/dev/null || err_exit "stopping midolman"
}

find_deb() {
    debdir=$1
    test -d $debdir || err_exit "could not find directory: $debdir"
    deb=`ls -t $debdir/*.deb 2>/dev/null | head -1`
    if [ -z "$deb" ] ; then
        err_exit "could not find deb file in: $debdir"
    fi
    echo $deb
}

install_midonet_api() {
    pushd $MIDONET_SRC_DIR
    test_phase "Installing MidoNet API"
    deb=`find_deb midonet-api/build/packages`
    test -f "$deb" || err_exit "deb file not found at: $deb"

    /etc/init.d/tomcat7 stop
    dpkg --purge midonet-api
    dpkg -i $deb || err_exit "installing $deb"
    cp midonet-api/src/main/webapp/WEB-INF/web.xml.dev \
       /usr/share/midonet-api/WEB-INF/web.xml

    [ -d $TOMCAT7_CONF_DIR ] || mkdir -p $TOMCAT7_CONF_DIR
    cp -f midonet-api/conf/midonet-api.xml $TOMCAT7_CONF_DIR/

    popd
    /etc/init.d/tomcat7 start || err_exit "starting midonet-api"
    sleep 10
    /etc/init.d/tomcat7 status | grep "is running" >/dev/null
    [ $? -eq 0 ] || err_exit "check that tomcat is running"
    wget_output=$(wget --spider --tries 1 $API_URL  2>&1)
    if [ $? -ne 0 ]; then
        echo "$wget_output"
        err_exit "api not responsive"
    fi
}

install_midolman() {
    pushd $MIDONET_SRC_DIR
    test_phase "Installing Midolman"
    mm_deb=`find_deb midolman/build/packages`
    test -f "$mm_deb" || err_exit "deb file not found at: $mm_deb"

    test -f $mm_deb || err_exit "$mm_deb"
    stop_midolman
    dpkg --purge midolman
    dpkg -i $mm_deb || err_exit "installing $mm_deb"
    popd

    install_config_file midolman/midolman-akka.conf /etc/midolman/
    install_config_file midolman/midolman-env.sh /etc/midolman/
    profiles.d/isolation/midolman/midolman-conf.sh

     # set a meaningful name for the hprof file, just in case it's enabled...
    export HPROF_FILENAME=$REPORT_DIR/midolman.hprof

    start midolman || err_exit "starting midolman"
    sleep 10
    output=`status midolman | grep start/running`
    [ $? -eq 0 ] || err_exit "check that midolman is running"
    MIDOLMAN_PID=`echo $output | sed -e 's/.*process //'`
    [ $? -eq 0 ] || err_exit "fetching midolman's pid"
}

stop_service() {
    status="0"
    /etc/init.d/$1 status || status="$?"
    if [ "$status" -ne 3 ] ; then
        /etc/init.d/$1 stop
    fi
}

start_service() {
    if [ -z $1 ] ; then
        err_exit "Usage: start_service NAME"
    fi
    test_phase "Starting $1"
    /etc/init.d/$1 start || err_exit "starting $1"
}


#######################################################################
# Actual tests
#######################################################################

connectivity_check() {
    test_phase "Connectivity check"
    ip netns exec $SOURCE_NETNS ping -c 5 $FLOOD_DEST_HOST || \
        err_exit "No connectivity between namespaces"
    ip netns exec $SOURCE_NETNS ping -c 5 $VICTIM_DEST_HOST || \
        err_exit "No connectivity between namespaces"
}

warm_up() {
    test_phase "Warming up midolman"

    ip netns exec $SOURCE_NETNS $LOADGEN --start 0 $FLOOD_SOURCE_DEV \
        $FLOOD_DEST_MAC 40000 2000

    sleep 5
}

start_monitoring() {
    # Start monitoring victim

    cashew_out="$(mktemp)" || exit 1
    trap "rm -rf $cashew_out" EXIT INT TERM HUP
    ip netns exec $VICTIM_DEST_NETNS ./cashew.sh -t /dev/null -l /dev/null \
        >$cashew_out 2>&1 &
    cashew_pid=$!

    sleep 1
}

stop_monitoring() {
    sleep 1

    kill -INT $cashew_pid
    wait $cashew_pid

    source $cashew_out
    rm -rf $cashew_out
    trap
}

mac_learning() {
    ip netns exec $FLOOD_DEST_NETNS ping -c 2 $FLOOD_SOURCE_HOST || \
        err_exit "No connectivity between flood namespaces"

    ip netns exec $VICTIM_DEST_NETNS ping -c 2 $VICTIM_SOURCE_HOST || \
        err_exit "No connectivity between victim namespaces"
}

measure() {
    local prefix=$1
    local loss_dat="$REPORT_DIR/$prefix.loss.dat"
    touch $loss_dat
    printf "# load/pps \t loss\n" >> $loss_dat
    local percentiles=('50' '75' '90' '99' '99_9')
    for pl in "${percentiles[@]}"
    do
        local dat="$REPORT_DIR/$prefix.$pl.dat"
        touch $dat
        printf "# load/pps \t latency/us\n" >> $dat
    done

    local pps=$BASE_PPS
    while [ $pps -lt $TOP_PPS ]
    do
        mac_learning

        echo "Sending $PACKETS_TO_SEND packets at $pps pps"

        if [ "$1" == "flood" ] ; then
            local to_send=$((DURATION_SECS * $FLOOD_PPS))
            ip netns exec $SOURCE_NETNS $LOADGEN 1 $FLOOD_SOURCE_DEV \
                $FLOOD_DEST_MAC $to_send $FLOOD_PPS
        fi

        start_monitoring

        local to_send=$((DURATION_SECS * pps))
        ip netns exec $SOURCE_NETNS $LOADGEN --start 0 $VICTIM_SOURCE_DEV \
            $VICTIM_DEST_MAC $to_send $pps

        stop_monitoring

        local loss=`echo "(1 - ($TOTAL_PACKETS / $to_send)) * 100" | bc -l`
        printf "$pps \t $loss\n" >> $loss_dat
        for pl in "${percentiles[@]}"
        do
            local val=`eval printf '$LAT_'$pl`
            printf "$pps \t $val\n" >> "$REPORT_DIR/$prefix.$pl.dat"
        done

        pps=$((pps + INCREMENT_PPS))
    done
 
    sleep 5
}

common_plot() {
    echo "set terminal svg fname 'Gill Sans' fsize 9 rounded dashed"
    echo "set key outside bottom right"
    echo "set style line 11 lc rgb '#808080' lt 1"
    echo "set border 3 back ls 11"
    echo "set tics nomirror"
    echo "set style line 12 lc rgb '#808080' lt 0 lw 1"
    echo "set grid back ls 12"
    echo "set autoscale xy"
    echo "set style line 1 lc rgb '#0060AD' lt 1 lw 2 pt 7 ps 1.5"
    echo "set style line 2 lc rgb '#AC2929' lt 1 lw 2 pt 7 ps 1.5"
    echo "set style data linespoints"
    echo "set xlabel 'load/pps'"
}

plot() {
    (
        common_plot
        echo "set ylabel 'loss/%'"
        echo "set title 'Packet loss'"
        echo "plot '$REPORT_DIR/baseline.loss.dat' ls 1 title ' baseline', \
                   '$REPORT_DIR/flood.loss.dat' ls 2 title ' flood'"
    ) | gnuplot > $REPORT_DIR/loss.svg

    local percentiles=('50' '75' '90' '99')
    for pl in "${percentiles[@]}"
    do
        (
            common_plot
            echo "set ylabel 'latency/us'"
            echo "set title 'Latency $pl%ile'"
            echo "plot '$REPORT_DIR/baseline.$pl.dat' ls 1 title ' baseline', \
                       '$REPORT_DIR/flood.$pl.dat' ls 2 title ' flood'"
        ) | gnuplot > "$REPORT_DIR/lat_${pl}.svg"
    done
    (
        common_plot
        echo "set ylabel 'latency/us'"
        echo "set title 'Latency 99.9%ile'"
        echo "plot '$REPORT_DIR/baseline.99_9.dat' ls 1 title ' baseline', \
                   '$REPORT_DIR/flood.99_9.dat' ls 2 title ' flood'"
    ) | gnuplot > $REPORT_DIR/lat_99_9.svg
}

verify_isolation() {
    test_phase "Measuring baseline..."
    measure "baseline"

    test_phase "Verifying isolation..."
    measure "flood"

    test_phase "Plotting..."
    plot
}

########################################################################
# Utility functions
########################################################################

add_if() {
    if [ $# -lt 4 ] ; then
        err_exit "Usage: add_if IF NS MAC ADDR"
    fi
    if="$1"
    ns="$2"
    dpif="${if}dp"
    nsif="${if}ns"
    mac="$3"
    addr="$4"

    echo "-------------------------------------------------------------"
    echo "Creating -$if- in -$ns- with MAC -$mac- and address -$addr-"
    echo "-------------------------------------------------------------"

    ip link add name $dpif type veth peer name $nsif || return 1
    ip link set $dpif up || return 1
    ip link set $nsif netns $ns || return 1
    ip netns exec $ns ip link set address $mac dev $nsif || return 1
    ip netns exec $ns ip link set $nsif up || return 1
    ip netns exec $ns ip addr add $addr dev $nsif || return 1
    ip netns exec $ns ifconfig lo up || return 1
}

cleanup_if() {
    if [ $# -lt 2 ] ; then
        echo "Usage: cleanup_if IF NS"
    fi
    if="$1"
    ns="$2"
    dpif="${if}dp"
    nsif="${if}ns"

    ip netns list | grep "^$ns$" >/dev/null
    if [ $? -eq 1 ] ; then
        return 0
    fi

    ip netns exec $ns ip link set lo down
    ip netns exec $ns ip link set $nsif down
    ip link delete $dpif
}

#######################################################################
# Test scenario setup and tear down functions
#######################################################################

print_topology() {
    echo "Virtual topology"
    echo "----------------"
    echo "    port bindings for host $HOST_ID:"
    midonet-cli -A -e host $HOST_ID list binding
    echo ""
    echo "    bridge listing:"
    midonet-cli -A -e bridge list
    if [ ! -z "$BR_ID" ] ; then
        echo ""
        echo "    bridge $BR_ID port listing:"
        midonet-cli -A -e bridge $BR_ID port list
    fi
}

setup_topology() {
    test_phase "Setting up virtual topology"

    while read host ; do
        HOST_ID=`echo $host | cut -d ' ' -f 2`
        break
    done < <(midonet-cli -A -e host list)

    if [ -z "$HOST_ID" ] ; then
        return 1
    fi
    echo "found host with id $HOST_ID"

    echo "Creating tunnel zone"
    TZONE_ID=`midonet-cli -A -e tunnel-zone create type gre name default`
    midonet-cli -A -e tunnel-zone $TZONE_ID \
        add member host $HOST_ID address 10.0.2.15

    echo "creating bridge"
    BR_ID=`midonet-cli -A -e bridge create name perftest-bridge`

    CHAIN_ID=`midonet-cli -A -e chain create name perftest-chain`
    midonet-cli -A -e chain $CHAIN_ID add rule src-port 0-65535 \
        dst-port 0-65535 type accept
    midonet-cli -A -e bridge $BR_ID set infilter $CHAIN_ID

    echo "creating ports"
    FLOODSOURCEPORT=`midonet-cli -A -e bridge $BR_ID create port`
    FLOODDESTPORT=`midonet-cli -A -e bridge $BR_ID create port`
    VICTIMSOURCEPORT=`midonet-cli -A -e bridge $BR_ID create port`
    VICTIMDESTPORT=`midonet-cli -A -e bridge $BR_ID create port`

    echo "creating bindings"
    midonet-cli -A -e host $HOST_ID add binding \
        interface $FLOOD_SOURCE_BINDING\
        port bridge $BR_ID port $FLOODSOURCEPORT > /dev/null
    midonet-cli -A -e host $HOST_ID add binding \
        interface $FLOOD_DEST_BINDING \
        port bridge $BR_ID port $FLOODDESTPORT > /dev/null
    midonet-cli -A -e host $HOST_ID add binding \
        interface $VICTIM_SOURCE_BINDING\
        port bridge $BR_ID port $VICTIMSOURCEPORT > /dev/null
    midonet-cli -A -e host $HOST_ID add binding \
        interface $VICTIM_DEST_BINDING \
        port bridge $BR_ID port $VICTIMDESTPORT > /dev/null

    echo "flood source port: $FLOODSOURCEPORT"
    echo "flood dest port: $FLOODDESTPORT"
    echo "victim source port: $VICTIMSOURCEPORT"
    echo "victim dest port: $VICTIMDESTPORT"
    echo "bridge: $BR_ID"
    echo "chain: $CHAIN_ID"
    echo "host: $HOST_ID"

    print_topology
}

tear_down_topology() {
    if [ -z "$BR_ID" ] ; then
        return
    fi
    test_phase "Tearing down virtual topology"
    midonet-cli -A -e host $HOST_ID delete binding interface $FLOOD_SOURCE_BINDING
    midonet-cli -A -e host $HOST_ID delete binding interface $FLOOD_DEST_BINDING
    midonet-cli -A -e host $HOST_ID delete binding interface $VICTIM_SOURCE_BINDING
    midonet-cli -A -e host $HOST_ID delete binding interface $VICTIM_DEST_BINDING
    midonet-cli -A -e delete tunnel-zone $TZONE_ID
    midonet-cli -A -e bridge $BR_ID delete port $FLOODSOURCEPORT
    midonet-cli -A -e bridge $BR_ID delete port $FLOODDESTPORT
    midonet-cli -A -e bridge $BR_ID delete port $VICTIMSOURCEPORT
    midonet-cli -A -e bridge $BR_ID delete port $VICTIMDESTPORT
    midonet-cli -A -e bridge $BR_ID delete
    midonet-cli -A -e chain $CHAIN_ID delete
    BR_ID=
    CHAIN_ID=
    FLOODSOURCEPORT=
    FLOODDESTPORT=
    VICTIMSOURCEPORT=
    VICTIMDESTPORT=

    print_topology
}


create_scenario() {
    test_phase "Creating test scenario"
    ip netns add $SOURCE_NETNS || return 1
    ip netns add $VICTIM_DEST_NETNS || return 1
    ip netns add $FLOOD_DEST_NETNS || return 1
    add_if "flood-source" $SOURCE_NETNS $FLOOD_SOURCE_MAC $FLOOD_SOURCE_NET
    add_if "flood-dest" $FLOOD_DEST_NETNS $FLOOD_DEST_MAC $FLOOD_DEST_NET
    add_if "victim-source" $SOURCE_NETNS $VICTIM_SOURCE_MAC $VICTIM_SOURCE_NET
    add_if "victim-dest" $VICTIM_DEST_NETNS $VICTIM_DEST_MAC $VICTIM_DEST_NET
    setup_topology
}

destroy_scenario() {
    test_phase "Destroying test scenario"
    tear_down_topology
    cleanup_if "flood-source" $SOURCE_NETNS
    cleanup_if "flood-dest" $FLOOD_DEST_NETNS
    cleanup_if "victim-source" $SOURCE_NETNS
    cleanup_if "victim-dest" $VICTIM_DEST_NETNS
    ip netns delete $SOURCE_NETNS
    ip netns delete $FLOOD_DEST_NETNS
    ip netns delete $VICTIM_DEST_NETNS
}

########################################################################
# Script body
########################################################################

setup_only="no"

if [ "$1" == "-s" ] || [ "$1" == "--setup" ] ; then
    setup_only="yes"
fi

if [ `id -u` != '0' ] ; then
    echo "$0: may only be run as root" >&2
    exit 1
fi

pushd `dirname $0`

echo "Executing test.."

setup_tests

if [ "$setup_only" != "yes" ] ; then
    warm_up
    verify_isolation
    build_report
    do_cleanup
fi

popd

