#!/bin/bash
#
# Copyright 2011 Midokura Europe SARL
#

BASE_IMAGE=$1
MACHINE_NAME=$2
TARGET_FILE=$3
LOCAL_AS=$4
PEER_AS=$5
PEER_IP=$6

. functions.sh

make_new_image "${BASE_IMAGE}" "${TARGET_FILE}"

kill_nbd_client

MOUNT_POINT=mnt/image_${MACHINE_NAME}

mount_image "${TARGET_FILE}" ${MOUNT_POINT}

setup_hostname ${MOUNT_POINT} ${MACHINE_NAME}

setup_quagga ${MOUNT_POINT} ${LOCAL_AS} ${PEER_AS} ${PEER_IP}

# ip, netmask, broadcast addr, gateway address
setup_network_config ${MOUNT_POINT} 10.10.173.2 255.255.255.0 10.10.173.255 10.10.173.1

unmount_image ${MOUNT_POINT}

kill_nbd_client

chmod 777 "${TARGET_FILE}"
