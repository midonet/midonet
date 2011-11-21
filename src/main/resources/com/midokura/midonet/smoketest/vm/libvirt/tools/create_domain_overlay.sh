#!/bin/bash
#
# Copyright 2011 Midokura Europe SARL
#

BASE_IMAGE=$1
MACHINE_NAME=$2
TARGET_FILE=$3

NBD_DEVICE=/dev/nbd0

function kill_nbd_client() {
    NBD_CLIENT_PIDS=`sudo pidof nbd-client`
    if [ "x${NBD_CLIENT_PIDS}x" != "xx" ]; then
        echo ${NBD_CLIENT_PIDS} | sudo xargs kill -9
    fi
}

echo Creating overlay image: \"${TARGET_FILE}\" using hostname \"${MACHINE_NAME}\" with base image: \"${BASE_IMAGE}\"

qemu-img create -b "${BASE_IMAGE}" -f qcow2 "${TARGET_FILE}"

kill_nbd_client

echo "Starting qemu-nbd server for the file \"${TARGET_FILE}\" "
qemu-nbd -v -p 2049 -b 127.0.0.1 "${TARGET_FILE}" &

sleep 1
echo "Starting nbd-client connected to the device"
sudo nbd-client localhost 2049 ${NBD_DEVICE}

sleep 0.25
stat ${NBD_DEVICE}p1 2>&1 1>/dev/null
while [ $? -ne 0 ]; do
        echo "Waiting for the ndb client to read the partitions"
        sleep 5;
        stat ${NBD_DEVICE}p1 2>&1 1>/dev/null
done

echo "Mounting overlay image to folder `pwd`/mnt/image_${MACHINE_NAME}"
mkdir -p mnt/image_${MACHINE_NAME}
sudo mount ${NBD_DEVICE}p1 mnt/image_${MACHINE_NAME}

HOSTNAME=`cat mnt/image_${MACHINE_NAME}/etc/hostname`
echo "Found machine hostname to be: ${HOSTNAME}"
echo "Changing it to: ${MACHINE_NAME}"

sudo sed -i.bak -e "s/^${HOSTNAME}$/${MACHINE_NAME}/g" mnt/image_${MACHINE_NAME}/etc/hostname
sudo sed -i.bak -e "s/^\([0-9\.]\+\) ${HOSTNAME}\.\([^ ]\+\) ${HOSTNAME}$/\1 ${MACHINE_NAME}.\2 ${MACHINE_NAME}/g" mnt/image_${MACHINE_NAME}/etc/hosts

echo "Unmounting the changed image"
sudo umount -l mnt/image_${MACHINE_NAME}

echo "Disconnecting the nbd-client connected device: ${NBD_DEVICE}"
sudo nbd-client -d ${NBD_DEVICE}

kill_nbd_client

rm -rf mnt/image_${MACHINE_NAME}
chmod 777 "${TARGET_FILE}"
