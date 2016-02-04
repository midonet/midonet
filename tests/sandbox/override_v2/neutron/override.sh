#!/bin/bash

# Install dependencies (advanced neutron services)
apt-get -qy install python-neutron-vpnaas python-neutron-fwaas python-neutron-lbaas

# Installs newest package (lexicographycally) in override
LATEST_CLIENT=$(ls override/python-midonetclient*deb | tail -n1)
# This only works for kilo, liberty name changed to python-networking-midonet
LATEST_PLUGIN=$(ls override/python-neutron*deb | tail -n1)
dpkg -i --force-confnew --force-confmiss $LATEST_PLUGIN $LATEST_CLIENT

cp /override/midonet.ini /etc/neutron/plugins/midonet/midonet.ini

# Run neutron server
exec /run-neutron.sh
