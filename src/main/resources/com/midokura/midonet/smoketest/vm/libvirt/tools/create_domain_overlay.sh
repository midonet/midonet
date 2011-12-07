#!/bin/bash
#
# Copyright 2011 Midokura Europe SARL
#

BASE_IMAGE=$1
MACHINE_NAME=$2
TARGET_FILE=$3

. `dirname $0`/functions.sh

sudo rm -f "${TARGET_FILE}"


make_new_image "${BASE_IMAGE}" "${TARGET_FILE}"

kill_nbd_client

MOUNT_POINT=mnt/image_${MACHINE_NAME}

mount_image "${TARGET_FILE}" ${MOUNT_POINT}

setup_hostname ${MOUNT_POINT} ${MACHINE_NAME}

unmount_image ${MOUNT_POINT}

kill_nbd_client

chmod 777 "${TARGET_FILE}"
