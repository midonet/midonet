#!/bin/bash
#
# Copyright 2011 Midokura Europe SARL
#

NBD_DEVICE=/dev/nbd0

function make_new_image
{
    echo Creating overlay image: \"${2}\" from base image: \"${1}\"

    qemu-img create -b "${1}" -f qcow2 "${2}"
}

function kill_nbd_client() {
    NBD_CLIENT_PIDS=`sudo pidof nbd-client`
    if [ "x${NBD_CLIENT_PIDS}x" != "xx" ]; then
        echo ${NBD_CLIENT_PIDS} | sudo xargs kill -9
    fi
}

function mount_image() {
    echo "Starting qemu-nbd server for the file \"${1}\" "
    qemu-nbd -v -p 2049 -b 127.0.0.1 "${1}" &

    sleep 0.25
    echo "Starting nbd-client connected to the device"
    sudo nbd-client localhost 2049 ${NBD_DEVICE}

    sleep 0.25
    stat ${NBD_DEVICE}p1 2>&1 1>/dev/null
    while [ $? -ne 0 ]; do
        echo "Waiting for the ndb client to read the partitions"
        sleep 0.5;
        stat ${NBD_DEVICE}p1 2>&1 1>/dev/null
    done

    echo "Mounting overlay image to folder `pwd`/${2}"
    mkdir -p ${2}
    sudo mount ${NBD_DEVICE}p1 ${2}
}

function unmount_image() {
    echo "Unmounting the updated image"
    sudo umount -l ${1}

    echo "Disconnecting the nbd-client connected device: ${NBD_DEVICE}"
    sudo nbd-client -d ${NBD_DEVICE}

    rm -rf ${1}
}

function update_hostname() {
    HOSTNAME=`cat ${2}/etc/hostname`
    echo "Found machine hostname to be: ${HOSTNAME}"
    echo "Changing it to: ${1}"

    sudo sed -i.bak -e "s/^${HOSTNAME}$/${1}/g" ${2}/etc/hostname
    sudo sed -i.bak -e "s/^\([0-9\.]\+\) ${HOSTNAME}\.\([^ ]\+\) ${HOSTNAME}$/\1 ${1}.\2 ${1}/g" ${2}/etc/hosts
}

function setup_quagga() {

    echo "Updating the /etc/quagga/vtysh.conf file"
    sudo cp "${1}/usr/share/doc/quagga/examples/vtysh.conf.sample" "${1}/etc/quagga/vtysh.conf"

    echo "Updating the /etc/quagga/zebra.conf file"
    sudo cp "${1}/usr/share/doc/quagga/examples/zebra.conf.sample" "${1}/etc/quagga/zebra.conf"

    echo "Updating the /etc/quagga/bgpd.conf file"
    sudo tee ${1}/etc/quagga/bgpd.conf >/dev/null <<_SMOKE_TEST_
hostname bgpd
password zebra

! AS number that quagga belongs to.
router bgp ${2:-54321}

! IP address that quagga use.
bgp router-id 192.168.0.1

! The IP and AS of the peer.
neighbor ${4:-192.168.0.2} remote-as ${3:-12345}

! Network information that quagga advertises.
network 10.2.0.0/24

log file /var/log/quagga/bgpd.log
_SMOKE_TEST_

    echo "Updating the /etc/quagga/daemons file"
    sudo tee ${1}/etc/quagga/daemons >/dev/null <<_SMOKE_TEST_
zebra=yes
bgpd=yes
ospfd=no
ospf6d=no
ripd=no
ripngd=no
isisd=no
_SMOKE_TEST_
}