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

ID=$1 # a number between 0 and 7
DEV=$2
DST_MAC=$3
COUNT=$4
DELAY_NANOS=$5

# Thread config (per CPU)

PGDEV="/proc/net/pktgen/kpktgend_$ID"
echo "Removing all devices"
pgset "rem_device_all"
echo "Adding interface on thread kpktgend_$ID"
pgset "add_device $DEV"
echo "Setting max_before_softirq 10000"
pgset "max_before_softirq 10000"

# Device config

# We need to do alloc for every skb since we cannot clone here.
CLONE_SKB="clone_skb 0"
# NIC adds 4 bytes CRC
PKT_SIZE="pkt_size 60"

# the number of packets sent. 0 means forever.
COUNT="count $COUNT"

# ipg is inter packet gap. 0 means maximum speed.
IPG="delay $DELAY_NANOS"

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
