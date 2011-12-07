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

mount_image "${TARGET_FILE}" mnt/image_${MACHINE_NAME}

update_hostname ${MACHINE_NAME} mnt/image_${MACHINE_NAME}

unmount_image mnt/image_${MACHINE_NAME}

kill_nbd_client

chmod 777 "${TARGET_FILE}"
