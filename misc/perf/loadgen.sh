#!/bin/bash

# modprobe pktgen

lsmod | grep pktgen > /dev/null || modprobe pktgen

function pgset() {
    echo $1 > $PGDEV
    cat $PGDEV | fgrep "Result: OK:" > /dev/null || cat $PGDEV | fgrep Result
}

function pg() {
    echo inject > $PGDEV
    cat $PGDEV
}

START="no"

if [ "$1" == "-s" ] || [ "$1" == "--start" ] ; then
    START="yes"
    shift
fi

ID=$1 # a number between 0 and 7
DEV=$2
DST_MAC=$3
COUNT=$4
PPS=$5

# Thread config (per CPU)

PGDEV="/proc/net/pktgen/kpktgend_$ID"
echo "Adding interface on thread kpktgend_$ID"
pgset "rem_device_all $DEV"
pgset "add_device $DEV"
echo "Setting max_before_softirq 10000"
pgset "max_before_softirq 10000"

# Device config

PGDEV=/proc/net/pktgen/$DEV
echo "Configuring $PGDEV"
# the number of packets sent. 0 means forever.
pgset "count $COUNT"
# We need to do alloc for every skb since we cannot clone here.
pgset "clone_skb 0"
# NIC adds 4 bytes CRC
pgset "pkt_size 60"
# Rate at which to send
pgset "ratep $PPS"
# Random address with in the min-max range
pgset "flag IPDST_RND"
pgset "dst_min 10.0.0.0"
pgset "dst_max 10.255.255.255"
pgset "flag UDPSRC_RND"
pgset "udp_src_min 0"
pgset "udp_src_max 65534"
pgset "flag UDPDST_RND"
pgset "udp_dst_min 0"
pgset "udp_dst_max 65534"

pgset "dst_mac $DST_MAC"

if [ "$START" == "yes" ] ; then
    PGDEV=/proc/net/pktgen/pgctrl
    echo "Running... ctrl^C to stop"
    pgset "start"
    pgset "reset"
fi

