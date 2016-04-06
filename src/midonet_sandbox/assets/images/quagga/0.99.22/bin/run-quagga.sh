#!/bin/bash

FILE=/etc/quagga/bgpd.conf

# Wait for configured interfaces to be set up
# The name of each additional interface should be provided
# in an env var with the _IFACE suffix.
for IFACE in `env | grep -Ev "ENV" | grep _IFACE | cut -d= -f2 | sort -u`; do
    # TODO: change pipework by native docker networking once stable
    echo "Waiting for interface $IFACE to be up through pipework"
        timeout 300s pipework --wait -i $IFACE
        if [ $? -eq 124 ]; then
            echo "Interface $IFACE was not ready after 60s. Exiting..."
            exit 1
        fi
done

# Add vtysh pager for easy debugging
export VTYSH_PAGER=more

chown quagga.quaggavty /etc/quagga/*

sed -i -e 's/zebra=no/zebra=yes/' -e 's/bgpd=no/bgpd=yes/' /etc/quagga/daemons

# Pick the router-id of this quagga daemon
if [ -z $BGP_ROUTER_ID ]; then
    echo "No router-id specified, using the default 10.0.255.255"
    BGP_ROUTER_ID=10.0.255.255
fi
# Pick the AS of this quagga instance
if [ -z "$BGP_AS" ]; then
    echo "No BGP AS number specified for this quagga instance."
    echo "Specify it in your yaml flavor description as an environment variable. eg:"
    echo "environment:"
    echo "- BGP_AS=64512"
    exit 1
fi

function add_to_neighbor()
{
    echo "    neighbor $IP $1" >> $FILE
}

function add_neighbors_by_type()
{
    TYPE=$1

    # Pick the IPs and ASs of the peering midolman and other quaggas through
    # links or environm ent variables. Rewrite bgpd.conf to point to peering
    # midolmans
    for PEER in `env | grep -E "$TYPE" | grep IP_AS | cut -d= -f2 | sort -u`; do
        IP=$(echo $PEER | cut -d: -f1)
        AS=$(echo $PEER | cut -d: -f2)
        add_to_neighbor "remote-as $AS_PEER"
        add_to_neighbor "timers 1 3"
        add_to_neighbor "timers connect 3"
        if [ "$TYPE" == "BGPPEER" ]; then
            add_to_neighbor "advertisement-interval 0"
            add_to_neighbor "next-hop-self"
            if [ "$BGP_DISABLE_ADVERTISING" == "yes" ]; then
                add_to_neighbor "filter-list access-list-0 out"
            fi
        fi
    done
}

add_neighbors_by_type BGPPEER
add_neighbors_by_type MIDOLMAN

# Write advertised networks on bgpd config file if specified
for NETWORK in `env | grep ADVERTISED_NETWORK | cut -d= -f2`; do
    echo "    network $NETWORK" >> $FILE
    # FIXME: Temporal workaround for backwards compat, it should be specified
    # wether it should advertise the default route through an ENV VAR.
    echo "    network 0.0.0.0/0" >> $FILE
    # Set the network to the lo interface to emulate internet access
    ip addr add $NETWORK dev lo
done

# Set max paths if defined
if [ -n "$BGP_MAX_PATHS" ]; then
    echo "Setting max_paths to $BGP_MAX_PATHS"
    sed -i "s/maximum-paths 2/maximum-paths $BGP_MAX_PATHS/" $FILE
fi

if [ "$BGP_DISABLE_ADVERTISING" == "yes" ]; then
  echo 'ip as-path access-list access-list-0 permit ^$' >> $FILE
fi

# Setting env vars in bgpd config file
sed -i "s/bgp router-id 10.255.255.255/bgp router-id $BGP_ROUTER_ID/" $FILE
sed -i "s/router bgp 64512/router bgp $BGP_AS/" $FILE

export VTYSH_PAGER=more

cat >> /etc/bash.bashrc <<-EOF
	
	# set vtysh pager
	export VTYSH_PAGER=more
EOF

echo Starting Quagga...
exec /sbin/init
