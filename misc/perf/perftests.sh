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

# This file contains the basic framework for the performance tests into
# which various topologies can be plugged.
#
# The test will send traffic from TOPOLOGY_SOURCE_NS to
# TOPOLOGY_DEST_HOST, custom topologies may decide how to set up
# physical and virtual topologies to configure different types of tests.
#
# To use:
# - Write a separate file implementing the required functions
# - Exec ./perftests.sh <test_name>
#
# Where test_name should be the file containing the topologies
#
# The methods expected in the given file are:
# - setup_topology:
#   Responsible to create the virtual topology binding the ingress and
#   egress ports to $TOPOLOGY_SOURCE_BINDING and $TOPOLOGY_DEST_BINDING,
#   respectively.
# - tear_down_topology:
#   Responsible to tear down the topology. It should destroy any virtual
#   entities created in setup_topology, as well as the interfaces bound
#   to the local interfaces


#######################################################################
# External configuration variables
#######################################################################

# Maximum rate (packets/sec) to try when looking for MM's max throughput
THROUGHPUT_SCAN_MAX_RATE=40000
# Number of port scans (50k ports each) to perform during the long-running test
LONG_RUNNING_SCAN_ITERATIONS=10
# Rate (packets/sec) at which to perform the scans in the long-running test
LONG_RUNNING_SCAN_RATE=10000

# Configuration for the rrd-based charts produced with the test report
# Graph width in pixels
GRAPH_WIDTH=1400
# Graph height in pixels
GRAPH_HEIGHT=500
# Upper limit on throughput graph (to tame spikes)
GRAPH_THROUGHPUT_UPPER_LIMIT=35000

#######################################################################
# Globals to be provided by the topology
#######################################################################

TOPOLOGY_SOURCE_HOST=
TOPOLOGY_DEST_HOST=
TOPOLOGY_SOURCE_NET=
TOPOLOGY_DEST_NET=
TOPOLOGY_SOURCE_MAC="aa:bb:cc:00:00:22"
TOPOLOGY_DEST_MAC="aa:bb:cc:00:00:44"

#######################################################################
# Private globals
#######################################################################

BASEDIR=/var/lib/midonet-perftests
TMPDIR=/tmp/midonet-perftests
LOGFILE=$TMPDIR/perftests.log
TMP_RRDDIR=/tmp/midonet-perftests/target

LOADGEN=./loadgen.sh

HOST=
PROFILE=
GITREV=
ABBREV_GITREV=
MIDOLMAN_PID=

TSTAMP=
TEST_ID=

RRD_DIR=
GRAPH_DIR=
HEAP_DUMP_PATH=
REPORT_DIR=

MIDONET_SRC_DIR=../..

GRAPH_START=
GRAPH_END=
GRAPH_OPTS=

TOPOLOGY_SOURCE_NETNS="left"
TOPOLOGY_DEST_NETNS="right"
TOPOLOGY_SOURCE_BINDING=leftdp
TOPOLOGY_DEST_BINDING=rightdp

HOST_ID=

# Use these options to get the results uploaded somewhere
UPLOAD_USER=
UPLOAD_HOST=
UPLOAD_DESTDIR=
UPLOAD_KEY=

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
    rm -f /var/log/jmxtrans/jmxtrans.log
}

do_cleanup() {
    test_phase "Cleaning up"
    stop_service jmxtrans
    destroy_scenario
    stop_midolman
    stop_service tomcat7
    stop_service cassandra
    stop_service zookeeper
    rm -rf /var/lib/zookeeper/data
}

source_config() {
    if [ -f profiles.d/$PROFILE/perftests.conf ] ; then
        . profiles.d/$PROFILE/perftests.conf
    elif [ -f profiles.d/default/perftests.conf ] ; then
        . profiles.d/default/perftests.conf
    fi
    if [ -f $HOME/.midonetperfrc ] ; then
        . $HOME/.midonetperfrc
    fi
}

assert_dependencies() {
    which vconfig || err_exit "vconfig not installed (apt-get install vlan)"
    which midonet-cli || err_exit "midonet-cli not installed"
    which rrdtool || err_exit "rrdtool not installed"
    test -f $MIDONET_SRC_DIR/midolman/build.gradle || err_exit "directory $MIDONET_SRC_DIR not a midonet code checkout"
    test -f $HOME/.midonetrc || err_exit ".midonetrc not found in $HOME"
    test -f $UPLOAD_KEY || err_exit "upload ssh key not found at $UPLOAD_KEY"
}

setup_tests() {
    assert_dependencies
    umask 0022
    mkdir -p $TMPDIR
    start_logging
    gather_build_info
    do_cleanup
    stop_jmxtrans
    start_service zookeeper
    start_service cassandra
    start_service tomcat7
    source_config
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
    pushd $MIDONET_SRC_DIR
    HOST=`hostname`
    GITREV=`git log -1 --pretty=format:%H`
    [ $? -eq 0 ] || err_exit "running git log"
    ABBREV_GITREV=`git log -1 --pretty=format:%h`
    [ $? -eq 0 ] || err_exit "running git log"
    popd

    TSTAMP=`date +%Y%m%d-%H%M%S`
    TEST_ID="$HOST-$TSTAMP-$ABBREV_GITREV"
    mkdir -p $BASEDIR
    REPORT_DIR="$BASEDIR/$TEST_ID"
    RRD_DIR="$REPORT_DIR/rrd"
    GRAPH_DIR="$REPORT_DIR/graphs"
    HEAP_DUMP_PATH="$REPORT_DIR/midolman.hprof"
    mkdir -p $RRD_DIR
    mkdir -p $GRAPH_DIR
    mkdir -p $REPORT_DIR
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

    if [ -f profiles.d/$PROFILE/$src ] ; then
        echo "installing file: $PROFILE/$src to $destdir"
        cp profiles.d/$PROFILE/$src $destdir
    elif [ -f profiles.d/default/$src ] ; then
        echo "installing file: default/$src to $destdir"
        cp profiles.d/default/$src $destdir
    fi
}

stop_midolman() {
    dpkg -s midolman > /dev/null 2>&1 || return 1
    status midolman | grep stop/waiting >/dev/null || stop midolman
    status midolman | grep stop/waiting >/dev/null || true
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
    popd
    /etc/init.d/tomcat7 start || err_exit "starting midonet-api"
    sleep 30
    /etc/init.d/tomcat7 status | grep "is running" >/dev/null
    [ $? -eq 0 ] || err_exit "check that tomcat is running"
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

    install_config_file midolman/logback.xml /etc/midolman/
    install_config_file midolman/midolman.conf /etc/midolman/
    install_config_file midolman/midolman-env.sh /etc/midolman/

    [ -d $TOMCAT7_CONF_DIR ] || mkdir -p $TOMCAT7_CONF_DIR
    cp -f $MIDONET_SRC_DIR/midonet-api/conf/midonet-api.xml $TOMCAT7_CONF_DIR/

     # set a meaningful name for the hprof file, just in case it's enabled...
    export HPROF_FILENAME=$REPORT_DIR/midolman-$TOPOLOGY_NAME-$PROFILE.hprof

    start midolman || err_exit "starting midolman"
    sleep 30
    output=`status midolman | grep start/running`
    [ $? -eq 0 ] || err_exit "check that midolman is running"
    MIDOLMAN_PID=`echo $output | sed -e 's/.*process //'`
    [ $? -eq 0 ] || err_exit "fetching midolman's pid"
}

midolman_heapdump() {
    test_phase "Dumping Midolman's Heap"
    jmap -dump:live,format=b,file=$HEAP_DUMP_PATH $MIDOLMAN_PID
    bzip2 $HEAP_DUMP_PATH
}

stop_jmxtrans() {
    test_phase "Stopping jmxtrans"
    /etc/init.d/jmxtrans stop || true
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

setup_jmxtrans() {
    test_phase "Setting up jmxtrans"
    install_config_file jmxtrans/default /etc/default/jmxtrans
    mkdir -p $TMP_RRDDIR
    chown jmxtrans $TMP_RRDDIR
    rm -rf $TMPDIR/jmxtrans-templates
    cp -a jmxtrans/templates $TMPDIR/jmxtrans-templates
    rm -rf $TMPDIR/jmxtrans-json
    cp -a jmxtrans/json $TMPDIR/jmxtrans-json
    start_service jmxtrans
    GRAPH_START=`date +%s`
}

#######################################################################
# Actual tests
#######################################################################

connectivity_check() {
    test_phase "Connectivity check"
    ip netns exec $TOPOLOGY_SOURCE_NETNS ping -c 10 $TOPOLOGY_DEST_HOST || \
        err_exit "No connectivity between namespaces"
}

warm_up() {
    test_phase "Warming up midolman"
    generate_traffic 1500 # 1500 pkts/sec
    sleep 10
}

test_throughput() {
    test_phase "Find max throughput by sending at a constant rate for 1 minute"
    rate=9000
    while [ $rate -le $THROUGHPUT_SCAN_MAX_RATE ] ; do
        generate_traffic $rate
        sleep 5
        let rate=rate+1000
    done
}

generate_traffic() {
    if [ -z $1 ] ; then
        err_exit "Usage: generate_traffic RATE"
    fi

    rate="$1"
    delay="$((1000000000 / $rate))"
    count="$((60 * rate))"

    srcif="${TOPOLOGY_SOURCE_NETNS}ns"
    dstif="${TOPOLOGY_DEST_NETNS}ns"

    echo "-----------------------------"
    echo "Generating traffic"
    echo "         rate: $rate pkt/s"
    echo "      packets: $count"
    echo "        delay: $delay ns"
    echo "-----------------------------"

    ip netns exec $TOPOLOGY_SOURCE_NETNS $LOADGEN 0 $srcif $TOPOLOGY_DEST_MAC \
        $count $delay || err_exit "generate traffic"
}

generate_traffic_with_capture() {
    if [ -z $1 ] || [ -z $2 ] ; then
        err_exit "Usage: generate_traffic_with_tcpdump NUM_PACKETS RATE"
    fi

    count="$1"
    rate="$2"
    delay="$((1000000000 / $rate))"

    srcif="${TOPOLOGY_SOURCE_NETNS}ns"
    dstif="${TOPOLOGY_DEST_NETNS}ns"

    echo "-----------------------------"
    echo "Generating traffic"
    echo "         rate: $rate pkt/s"
    echo "      packets: $count"
    echo "        delay: $delay ns"
    echo "-----------------------------"

    tcpdump_out=mktemp || err_exit "preparing tcp dump"
    trap 'rm -rf "$tcpdump_out"' EXIT INT TERM HUP
    ip netns exec $TOPOLOGY_DEST_NETNS tcpdump udp -i $dstif >$tcpdump_out 2>&1 &
    tcpdump_pid=$!

    ip netns exec $TOPOLOGY_SOURCE_NETNS $LOADGEN 0 $srcif $TOPOLOGY_DEST_MAC \
        $count $delay || err_exit "generate traffic"

    sleep 5 # Assuming the tail latency is never bigger than 5 seconds

    kill -INT $tcpdump_pid

    packets=`tac $tcpdump_out | grep -oP '\d+(?= packets received by filter)'`

    echo "Received $packets out of $count packets ($((($packets * 100) / $count))%)"
}

#######################################################################
# RRD Graph generation
#######################################################################

make_graphs() {
    test_phase "Generating graphs"

    mv $TMP_RRDDIR/*.rrd $RRD_DIR/
    test -f "$RRD_DIR/cpu.rrd" || err_exit "rrd files not found in $RRD_DIR"
    GRAPH_END=`rrdtool last $RRD_DIR/cpu.rrd`
    GRAPH_OPTS="--width=$GRAPH_WIDTH --height=$GRAPH_HEIGHT --start=$GRAPH_START --end=$GRAPH_END --border 0 --slope-mode"

    memory_graph     || err_exit "create memory graph"
    cpu_graph        || err_exit "create cpu graph"
    latency_graph    || err_exit "create latency graph"
    throughput_graph || err_exit "create throughput graph"
    flows_graph      || err_exit "create dpflows graph"
}

memory_graph() {
    rrdtool graph "$GRAPH_DIR/mem.png" \
        -t 'Midolman JVM Heap Memory Pools' \
        $GRAPH_OPTS -l 0 -b 1024 \
        "DEF:oldCommitted=$RRD_DIR/mem-cms.rrd:Usagefaf69c0b2860af:AVERAGE" \
        "DEF:oldUsed=$RRD_DIR/mem-cms.rrd:Usageb99c299c69dff0:AVERAGE" \
        "DEF:edenCommitted=$RRD_DIR/mem-eden.rrd:Usagefaf69c0b2860af:MAX" \
        "DEF:edenUsed=$RRD_DIR/mem-eden.rrd:Usageb99c299c69dff0:AVERAGE" \
        "DEF:survivorCommitted=$RRD_DIR/mem-survivor.rrd:Usagefaf69c0b2860af:MAX" \
        "DEF:survivorUsed=$RRD_DIR/mem-survivor.rrd:Usageb99c299c69dff0:AVERAGE" \
        'CDEF:oldFree=oldCommitted,oldUsed,-' \
        'CDEF:survivorFree=survivorCommitted,survivorUsed,-' \
        'AREA:oldUsed#CCCC00:Old Gen Used\n:STACK' \
        'AREA:oldFree#44cc44:Old Gen\n:STACK' \
        'AREA:survivorUsed#ff6600:Survivor Used\n:STACK' \
        'AREA:survivorFree#66aaee:Survivor\n:STACK' \
        'LINE2:edenCommitted#222222:Eden'
}

cpu_graph() {
    rrdtool graph "$GRAPH_DIR/cpu.png" \
        -t 'Midolman CPU Usage - 30 second running average (Percent)' \
        $GRAPH_OPTS -l 0 \
        "DEF:cpuLoadAvg=$RRD_DIR/cpu.rrd:ProcessCpuLoad97bb3:AVERAGE" \
        "DEF:cpuLoadMax=$RRD_DIR/cpu.rrd:ProcessCpuLoad97bb3:MAX" \
        "DEF:cpuTimeAvg=$RRD_DIR/cpu.rrd:ProcessCpuTime8e081:AVERAGE" \
        "DEF:cpuTimeMax=$RRD_DIR/cpu.rrd:ProcessCpuTime8e081:MAX" \
        'CDEF:cpuLoadAvgPct=cpuLoadAvg,100,*' \
        'CDEF:cpuLoadMaxPct=cpuLoadMax,100,*' \
        'CDEF:cpuLoadAvgPctTrend=cpuLoadAvgPct,30,TREND' \
        'CDEF:cpuLoadMaxPctTrend=cpuLoadMaxPct,30,TREND' \
        'AREA:cpuLoadAvgPct#555555:Avg CPU Load\n' \
        'LINE2:cpuLoadMaxPct#FF6622:Max CPU Load'
}

latency_graph() {
    rrdtool graph "$GRAPH_DIR/latency.png" \
        -t 'Midolman Simulation Latency (microsecs)' \
        $GRAPH_OPTS --units=si -u 1000000 --rigid -o \
        "DEF:simulations=$RRD_DIR/sim-meter.rrd:Count667cf906787399:AVERAGE" \
        "DEF:timeNanos=$RRD_DIR/sim-times.rrd:Count1656b5784b9c22:AVERAGE" \
        "DEF:98Nanos=$RRD_DIR/sim-latencies.rrd:98thPercentilef3450:AVERAGE" \
        "DEF:medianNanos=$RRD_DIR/sim-latencies.rrd:50thPercentilecb1f8:AVERAGE" \
        "DEF:75Nanos=$RRD_DIR/sim-latencies.rrd:75thPercentile04fec:AVERAGE" \
        "DEF:95Nanos=$RRD_DIR/sim-latencies.rrd:95thPercentile655dd:AVERAGE" \
        "DEF:99Nanos=$RRD_DIR/sim-latencies.rrd:99thPercentile41498:AVERAGE" \
        "DEF:999Nanos=$RRD_DIR/sim-latencies.rrd:999thPercentile8576:AVERAGE" \
        'CDEF:1secLatencyNanos=timeNanos,simulations,/' \
        'CDEF:1secLatencyMicro=1secLatencyNanos,1000,/' \
        'CDEF:999Micro=999Nanos,1000,/' \
        'CDEF:99Micro=99Nanos,1000,/' \
        'CDEF:98Micro=98Nanos,1000,/' \
        'CDEF:95Micro=95Nanos,1000,/' \
        'CDEF:75Micro=75Nanos,1000,/' \
        'CDEF:medianMicro=medianNanos,1000,/' \
        'AREA:99Micro#cc0000:Overall 99th pct\:\t' \
        'GPRINT:99Micro:LAST:%2.0lf microsecs\n' \
        'AREA:98Micro#dd6622:Overall 98th pct\:\t' \
        'GPRINT:98Micro:LAST:%2.0lf microsecs\n' \
        'AREA:95Micro#dd9922:Overall 95th pct\:\t' \
        'GPRINT:95Micro:LAST:%2.0lf microsecs\n' \
        'AREA:75Micro#ddcc22:Overall 75th pct\:\t' \
        'GPRINT:75Micro:LAST:%2.0lf microsecs\n' \
        'AREA:medianMicro#66dd66:Overall median\:\t' \
        'GPRINT:medianMicro:LAST:%2.0lf microsecs\n' \
        'LINE1:1secLatencyMicro#000000:1 second average'
}

throughput_graph() {
    rrdtool graph "$GRAPH_DIR/throughput.png" \
        -t 'Midolman throughput (packets/sec)' \
        $GRAPH_OPTS --upper-limit=$GRAPH_THROUGHPUT_UPPER_LIMIT --rigid --units=si \
        "DEF:processed=$RRD_DIR/packet-meter.rrd:Count667cf906787399:AVERAGE" \
        "DEF:dropped=$RRD_DIR/pipeline-drops.rrd:Value896c037a32087c:MAX" \
        "VDEF:maxthroughput=processed,MAXIMUM" \
        'LINE2:processed#66cc66:Packets processed\n' \
        'HRULE:maxthroughput#88ee88:Peak throughput: ' \
        'GPRINT:maxthroughput: %2.0lf pps\n' \
        'LINE2:dropped#cc6666:Packets dropped'
}

flows_graph() {
    rrdtool graph "$GRAPH_DIR/dpflows.png" \
        -t "Midolman Datapath Flow Table" \
        $GRAPH_OPTS --units=si \
        --right-axis '0.1:0' \
        --right-axis-label 'Flows/sec' \
        --vertical-label 'Flows' \
        "DEF:current=$RRD_DIR/dpflows.rrd:Valueeb5c5dcf0e47ed:AVERAGE" \
        "DEF:rate=$RRD_DIR/dpflows-meter.rrd:Count667cf906787399:MAX" \
        'CDEF:rate10=rate,10,*' \
        'AREA:current#555555:Active flows\n' \
        'LINE2:rate10#ff6622:Creation rate\n'
}

make_report() {
    test_phase "Creating test report"
    . /etc/midolman/midolman-env.sh

    while true ; do
        echo "git rev: $GITREV"
        echo "date: `date`"
        echo "test-id: $TEST_ID"
        echo "hostname: $HOST"
        echo "jvm max heap size: $MAX_HEAP_SIZE"
        echo "jvm heap new-size: $HEAP_NEWSIZE"
        echo "jvm opts: $JVM_OPTS"
        echo ""
        echo ""
        echo "Memory"
        echo "------"
        free -m
        echo ""
        echo ""
        echo "CPUs"
        echo "----"
        cat /proc/cpuinfo
        break
    done > "$REPORT_DIR/report.txt"

    while true ; do
        echo "gitrev,host,start,end,heap,heap_new,topology,profile"
        echo "$GITREV,$HOST,$GRAPH_START,$GRAPH_END,$MAX_HEAP_SIZE,$HEAP_NEWSIZE,$TOPOLOGY_NAME,$PROFILE"
        break
    done > "$REPORT_DIR/testspec.csv"

    mv $LOGFILE $REPORT_DIR/perftests.log
    mkdir -p $REPORT_DIR/mm-conf
    cp /etc/midolman/midolman.conf $REPORT_DIR/mm-conf/
    cp /etc/midolman/midolman-env.sh $REPORT_DIR/mm-conf/
    cp /var/log/midolman/midolman.log $REPORT_DIR/
    chmod -R go+r $REPORT_DIR
}

upload_report() {
    if [ -z $1 ] ; then
        err_exit "Usage: upload_report TEST_ID"
    fi
    test_id=$1
    test -d $BASEDIR/$test_id || \
        err_exit "could not find directory: $BASEDIR/$test_id"

    pushd $BASEDIR >/dev/null
    tar cjf $TMPDIR/$test_id.tar.bz2 $test_id || err_exit "creating results archive"
    popd >/dev/null
    touch $TMPDIR/$test_id.tar.bz2.perftest

    scp -i $UPLOAD_KEY $TMPDIR/$test_id.tar.bz2 \
        $UPLOAD_USER@$UPLOAD_HOST:$UPLOAD_DESTDIR || err_exit "uploading report"
    scp -i $UPLOAD_KEY $TMPDIR/$test_id.tar.bz2.perftest \
        $UPLOAD_USER@$UPLOAD_HOST:$UPLOAD_DESTDIR || err_exit "uploading report"

    rm -f $TMPDIR/$test_id.tar.bz2
    rm -f $TMPDIR/$test_id.tar.bz2.perftest
    rm -rf $BASEDIR/$test_id/
}


########################################################################
# Utility functions
########################################################################

add_ns() {
    if [ -z $1 ] ; then
        err_exit "Usage: add_ns NAME MAC ADDRESS"
    fi
    if [ -z $2 ] ; then
        err_exit "Usage: add_ns NAME MAC ADDRESS"
    fi
    ns="$1"
    dpif="${ns}dp"
    nsif="${ns}ns"
    mac="$2"
    addr="$3"

    echo "---------------------------------------------------------------------"
    echo "Creating network namespace $ns with mac $mac and address $addr"
    echo "---------------------------------------------------------------------"

    ip netns add $ns || return 1
    ip link add name $dpif type veth peer name $nsif || return 1
    ip link set $dpif up || return 1
    ip link set $nsif netns $ns || return 1
    ip netns exec $ns ip link set address $mac dev $nsif || return 1
    ip netns exec $ns ip link set $nsif up || return 1
    ip netns exec $ns ip addr add $addr dev $nsif || return 1
    ip netns exec $ns ip link set dev lo up || return 1
}

cleanup_ns() {
    if [ -z $1 ] ; then
        err_exit "Usage: cleanup_ns NAME"
    fi
    ns="$1"
    dpif="${ns}dp"
    nsif="${ns}ns"

    ip netns list | awk '{print $1}' | grep -q "^$ns$"
    if [ $? -eq 1 ] ; then
        return 0
    fi

    ip netns exec $ns ip link set lo down
    ip netns exec $ns ip link set $nsif down
    ip link delete $dpif
    ip netns delete $ns
}

#######################################################################
# Test scenario setup and tear down functions
#######################################################################

create_scenario() {
    test_phase "Creating test scenario"
    add_ns $TOPOLOGY_SOURCE_NETNS $TOPOLOGY_SOURCE_MAC $TOPOLOGY_SOURCE_NET
    add_ns $TOPOLOGY_DEST_NETNS $TOPOLOGY_DEST_MAC $TOPOLOGY_DEST_NET
    setup_topology
}

destroy_scenario() {
    test_phase "Destroying test scenario"
    tear_down_topology
    cleanup_ns $TOPOLOGY_DEST_NETNS
    cleanup_ns $TOPOLOGY_SOURCE_NETNS
}

########################################################################
# Script body
########################################################################

setup_only="no"

if [ "$1" == "-s" ] || [ "$1" == "--setup" ] ; then
    shift
    setup_only="yes"
fi

if [ -z "$1" ] ; then
    echo "Usage: perftests.sh [-s|--setup] scenario profile" >&2
    exit 1
fi

if [ `id -u` != '0' ] ; then
    echo "$0: may only be run as root" >&2
    exit 1
fi

pushd `dirname $0`

TOPOLOGY_NAME=$1
TOPOLOGY_FILE=topologies.d/$1
if [ ! -f "$TOPOLOGY_FILE" ] ; then
    err_exit "Topology file ($1) does not exist"
    exit 1
fi

echo "Sourcing topology description from $TOPOLOGY_FILE"
source $TOPOLOGY_FILE

echo "Setting up tests.."
PROFILE=$2
setup_tests

if [ "$setup_only" != "yes" ] ; then
    if [ -z $2 ] ; then
        echo "Usage: perftests.sh [-s|--setup] scenario profile" >&2
        exit 1
    fi

    setup_jmxtrans

    echo "Executing tests.."

    warm_up

    test_throughput
    stop_jmxtrans
    midolman_heapdump

    do_cleanup

    make_graphs
    make_report

    upload_report $TEST_ID
fi

popd
