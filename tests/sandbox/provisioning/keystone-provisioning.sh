#!/bin/bash -x

INDOCKER="docker exec mnsandboxmdts_keystone_1"
IP_ADDRESS=$($INDOCKER hostname --ip-address)

OS_SERVICE_TOKEN="ADMIN"
OS_SERVICE_ENDPOINT="http://$IP_ADDRESS:35357/v2.0"

KEYSTONE="$INDOCKER keystone --os-endpoint=$OS_SERVICE_ENDPOINT --os-token=$OS_SERVICE_TOKEN"
$INDOCKER keystone-manage db_sync


$KEYSTONE tenant-create --name admin --description "Admin tenant"
$KEYSTONE user-create --name admin --pass admin
$KEYSTONE role-create --name admin
$KEYSTONE role-create --name __member__
$KEYSTONE user-role-add --user admin --tenant admin --role admin
$KEYSTONE user-role-add --user admin --tenant admin --role __member__
$KEYSTONE tenant-create --name service --description "Service tenant"
$KEYSTONE service-create --name keystone --type identity --description "OSt identity"

SERVICE_ID=$($KEYSTONE service-list | awk  '/ identity / {print $2}' | xargs | cut -d' ' -f1)

$KEYSTONE endpoint-create \
  --service-id $SERVICE_ID \
  --publicurl http://$IP_ADDRESS:5000/v2.0 \
  --internalurl http://$IP_ADDRESS:5000/v2.0 \
  --adminurl http://$IP_ADDRESS:35357/v2.0 \
  --region regionOne
