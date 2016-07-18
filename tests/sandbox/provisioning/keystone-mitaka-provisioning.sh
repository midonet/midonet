#!/bin/bash -x

# It's recommended to use openstack client instead of keystone

INDOCKER="docker exec mnsandbox${SANDBOX_NAME}_keystone_1"
KEYSTONE_IP=$(docker exec mnsandbox${SANDBOX_NAME}_keystone_1 hostname --ip-address)
NEUTRON_IP=$(docker exec mnsandbox${SANDBOX_NAME}_neutron_1 hostname --ip-address)

OS_SERVICE_TOKEN="ADMIN"
OS_SERVICE_ENDPOINT="http://$KEYSTONE_IP:35357/v2.0"

KEYSTONE="$INDOCKER keystone --os-endpoint=$OS_SERVICE_ENDPOINT --os-token=$OS_SERVICE_TOKEN"
$INDOCKER keystone-manage db_sync

#Create the service entity and API endpoints
#Create domain projects, users, and roles
$INDOCKER keystone-manage bootstrap \
	--bootstrap-password admin \
	--bootstrap-username admin \
	--bootstrap-project-name admin \
	--bootstrap-role-name admin \
	--bootstrap-service-name keystone \
	--bootstrap-region-id RegionOne \
	--bootstrap-admin-url http://$KEYSTONE_IP:35357 \
	--bootstrap-public-url http://$KEYSTONE_IP:5000 \
	--bootstrap-internal-url http://$KEYSTONE_IP:5000

$KEYSTONE tenant-create --name service --description "Service tenant"
$KEYSTONE user-create --name neutron --pass neutron
$KEYSTONE user-role-add --user neutron --tenant service --role admin
$KEYSTONE user-role-add --user neutron --tenant service --role __member__

$KEYSTONE service-create --name neutron --type network --description "OSt network"

NEUTRON_SERVICE_ID=$($KEYSTONE service-list | awk  '/ network / {print $2}' | xargs | cut -d' ' -f1)

$KEYSTONE endpoint-create \
  --service-id $NEUTRON_SERVICE_ID \
  --publicurl http://$NEUTRON_IP:9696 \
  --internalurl http://$NEUTRON_IP:9696 \
  --adminurl http://$NEUTRON_IP:9696 \
  --region regionOne
