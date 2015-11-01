#!/bin/bash -x

INDOCKER="docker exec mnsandboxmdts_keystone_1"
KEYSTONE_IP=$(docker exec mnsandboxmdts_keystone_1 hostname --ip-address)
NEUTRON_IP=$(docker exec mnsandboxmdts_neutron_1 hostname --ip-address)

OS_SERVICE_TOKEN="ADMIN"
OS_SERVICE_ENDPOINT="http://$KEYSTONE_IP:35357/v2.0"

KEYSTONE="$INDOCKER keystone --os-endpoint=$OS_SERVICE_ENDPOINT --os-token=$OS_SERVICE_TOKEN"
$INDOCKER keystone-manage db_sync


$KEYSTONE role-create --name admin
$KEYSTONE role-create --name __member__

$KEYSTONE tenant-create --name admin --description "Admin tenant"
$KEYSTONE user-create --name admin --pass admin
$KEYSTONE user-role-add --user admin --tenant admin --role admin
$KEYSTONE user-role-add --user admin --tenant admin --role __member__

$KEYSTONE tenant-create --name service --description "Service tenant"
$KEYSTONE user-create --name neutron --pass neutron
$KEYSTONE user-role-add --user neutron --tenant service --role admin
$KEYSTONE user-role-add --user neutron --tenant service --role __member__

$KEYSTONE service-create --name keystone --type identity --description "OSt identity"
$KEYSTONE service-create --name neutron --type network --description "OSt network"

KEYSTONE_SERVICE_ID=$($KEYSTONE service-list | awk  '/ identity / {print $2}' | xargs | cut -d' ' -f1)
NEUTRON_SERVICE_ID=$($KEYSTONE service-list | awk  '/ network / {print $2}' | xargs | cut -d' ' -f1)

$KEYSTONE endpoint-create \
  --service-id $KEYSTONE_SERVICE_ID \
  --publicurl http://$KEYSTONE_IP:5000/v2.0 \
  --internalurl http://$KEYSTONE_IP:5000/v2.0 \
  --adminurl http://$KEYSTONE_IP:35357/v2.0 \
  --region regionOne

$KEYSTONE endpoint-create \
  --service-id $NEUTRON_SERVICE_ID \
  --publicurl http://$NEUTRON_IP:9696 \
  --internalurl http://$NEUTRON_IP:9696 \
  --adminurl http://$NEUTRON_IP:9696 \
  --region regionOne