#!/bin/bash

# modprobe pktgen

if [[ `lsmod | grep pktgen` == "" ]];then
   modprobe pktgen
fi

function pgset() {
    local result

    echo $1 > $PGDEV

    result=`cat $PGDEV | fgrep "Result: OK:"`
    if [ "$result" = "" ]; then
         cat $PGDEV | fgrep Result:
    fi
}

function pg() {
    echo inject > $PGDEV
    cat $PGDEV
}

# Config Start Here -----------------------------------------------------------

DEV=$1
DST_MAC=$2

# Thread config (per CPU)

PGDEV=/proc/net/pktgen/kpktgend_0
echo "Removing all devices"
pgset "rem_device_all"
echo "Adding interface"
pgset "add_device $DEV"
echo "Setting max_before_softirq 10000"
pgset "max_before_softirq 10000"

# Device config

# We need to do alloc for every skb since we cannot clone here.
CLONE_SKB="clone_skb 0"
# NIC adds 4 bytes CRC
PKT_SIZE="pkt_size 60"

# COUNT 0 means forever
#COUNT="count 0"
COUNT="count 10000000"

# ipg is inter packet gap. 0 means maximum speed.
IPG="delay 5" #5 nanoseconds

PGDEV=/proc/net/pktgen/$DEV
echo "Configuring $PGDEV"
pgset "$COUNT"
pgset "$CLONE_SKB"
pgset "$PKT_SIZE"
pgset "$IPG"
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

# Time to run
PGDEV=/proc/net/pktgen/pgctrl

echo "Running... ctrl^C to stop"
pgset "start"
echo "Done"

# http://www.programering.com/a/MTO5UzMwATI.html