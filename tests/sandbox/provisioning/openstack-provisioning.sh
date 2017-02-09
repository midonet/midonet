#!/bin/bash

# CLI variables
export MCLI_CLI="docker exec mnsandbox${SANDBOX_NAME}_cluster1_1"
export NEUTRON_CLI="docker exec mnsandbox${SANDBOX_NAME}_neutron_1"
export KEYST_CLI="docker exec mnsandbox${SANDBOX_NAME}_keystone_1"
export MIDOCLI_IP=$(docker exec mnsandbox${SANDBOX_NAME}_cluster1_1 hostname --ip-address)

# Openstack commands
export KEYSTONE="$KEYST_CLI keystone --os-endpoint=$OS_SERVICE_ENDPOINT --os-token=$OS_SERVICE_TOKEN"
export NEUTRON="$NEUTRON_CLI neutron --os-username=$OS_USERNAME --os-password=$OS_PASSWORD --os-tenant-name=$OS_USERNAME --os-auth-url=$OS_AUTH_URL"
export MIDOCLI="$MCLI_CLI midonet-cli -A -e"

# Network parameters

LOCALAS=64513
REMOTEAS1=64511
REMOTEAS2=64511
PUBNET=200.0.0.0/16

# create the tunnel zone

attempts=0
# Checking if midonet API is up
until [[ ${attempts} -gt 45 ]] || nc -zv $MIDOCLI_IP 8181 &> /dev/null; do
  attempts=$((attempts+1))
  sleep 5
done

OUT="$(mktemp)"
$MIDOCLI list host > $OUT

if $MIDOCLI list tunnel-zone 2>&1 | grep tz
then
        TZONE0=$($MIDOCLI list tunnel-zone | awk '{ print $2 }')
else
        TZONE0=$($MIDOCLI tunnel-zone create name tz type vxlan)
fi

# Docker uses the 172.16.0.0/16 networks
regex="host[[:space:]]([A-Za-z0-9._%+-]+).*(172\.17\.[0-9]{1,3}\.[0-9]{1,3})"

while read line
do  if [[ $line =~ $regex ]]
  then
   HOST_ID=${BASH_REMATCH[1]}
   HOST_IP=${BASH_REMATCH[2]}
   $MIDOCLI "tunnel-zone $TZONE0 add member host $HOST_ID address $HOST_IP"
  fi
done < $OUT

# Create the edge router and Floating IP networks
$NEUTRON net-create ext-net --router:external --shared
$NEUTRON subnet-create ext-net $PUBNET --name ext-subnet \
  --allocation-pool start=200.0.0.10,end=200.0.255.200 \
  --disable-dhcp --gateway 200.0.0.1  # CAPTURE THE SUBNETID IN A VAR
$NEUTRON router-create edge-router  # CAPTURE THE ROUTERID IN A VAR
$NEUTRON router-interface-add edge-router ext-subnet
# Create uplink networks
$NEUTRON net-create net-midolman1-gw1 --tenant_id admin --provider:network_type uplink
$NEUTRON net-create net-midolman2-gw2 --tenant_id admin --provider:network_type uplink
# Create subnets in every uplink network
$NEUTRON subnet-create --tenant_id admin --disable-dhcp --name subnet-midolman1-gw1 net-midolman1-gw1 10.1.0.0/24
$NEUTRON subnet-create --tenant_id admin --disable-dhcp --name subnet-midolman2-gw2 net-midolman2-gw2 10.2.0.0/24
# Create ports and bind them to the physical interfaces in the hosts
UPLINK1=$($NEUTRON port-create net-midolman1-gw1 --binding:host_id midolman1 --binding:profile type=dict interface_name=bgp0 --fixed-ip ip_address=10.1.0.1 | grep -w id | awk '{ print $4 }')
UPLINK2=$($NEUTRON port-create net-midolman2-gw2 --binding:host_id midolman2 --binding:profile type=dict interface_name=bgp0 --fixed-ip ip_address=10.2.0.1 | grep -w id | awk '{ print $4 }')
# Add the created ports to the edge router
$NEUTRON router-interface-add edge-router port=$UPLINK1
$NEUTRON router-interface-add edge-router port=$UPLINK2

#Setup BGP between midonet and external host
# Get the Edge Router ID
PROUTER=$($MIDOCLI router list | grep edge-router | awk '{ print $2 }')
# Add to ports to peer BGP
$MIDOCLI router $PROUTER set asn $LOCALAS
$MIDOCLI router $PROUTER add bgp-peer asn $REMOTEAS1 address 10.1.0.240
$MIDOCLI router $PROUTER add bgp-peer asn $REMOTEAS2 address 10.1.0.241
$MIDOCLI router $PROUTER add bgp-peer asn $REMOTEAS1 address 10.2.0.240
$MIDOCLI router $PROUTER add bgp-peer asn $REMOTEAS2 address 10.2.0.241
$MIDOCLI router $PROUTER add bgp-network net $PUBNET
# Wait until BGP converge
sleep 100
