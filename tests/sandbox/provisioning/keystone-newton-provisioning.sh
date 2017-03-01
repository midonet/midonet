#!/bin/bash -x

INDOCKER="docker exec mnsandbox${SANDBOX_NAME}_keystone_1"

KEYSTONE_IP=$(docker exec mnsandbox${SANDBOX_NAME}_keystone_1 hostname --ip-address)
NEUTRON_IP=$(docker exec mnsandbox${SANDBOX_NAME}_neutron_1 hostname --ip-address)

OS_SERVICE_ENDPOINT="http://$KEYSTONE_IP:5000"

OPENSTACK="$INDOCKER openstack --os-auth-url=$OS_SERVICE_ENDPOINT"

$INDOCKER keystone-manage db_sync

KEYSTONE_SERVICE_ID=$($OPENSTACK service list | awk  '/ identity / {print $2}' | xargs | cut -d' ' -f1)
NEUTRON_SERVICE_ID=$($OPENSTACK service list | awk  '/ network / {print $2}' | xargs | cut -d' ' -f1)

$OPENSTACK endpoint create \
  --publicurl http://$NEUTRON_IP:9696 \
  --internalurl http://$NEUTRON_IP:9696 \
  --adminurl http://$NEUTRON_IP:9696 \
  --region regionOne $NEUTRON_SERVICE_ID
