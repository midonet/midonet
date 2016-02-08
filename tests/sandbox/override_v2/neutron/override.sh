#!/bin/bash

# FIXME: We get the plugin from internal artifactory to run vpnaas for kilo.
# The package should come as a parameter in the CI (this needs some work).
REPO_URL="http://artifactory.bcn.midokura.com/artifactory/"
PACKAGE_DIST="openstack-kilo-deb/deb/unstable/"
PACKAGE_NAME="python-neutron-plugin-midonet_2015.1.1-2_all.deb"
wget -O /packages/$PACKAGE_NAME $REPO_URL$PACKAGE_DIST$PACKAGE_NAME

# Install dependencies (advanced neutron services)
apt-get -qy install python-neutron-vpnaas python-neutron-fwaas python-neutron-lbaas

# Install the latest from local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# This only works for kilo, liberty name changed to python-networking-midonet
apt-get install -qy --force-yes python-neutron-plugin-midonet/local \
                                python-midonetclient/local

cp /override/midonet.ini /etc/neutron/plugins/midonet/midonet.ini

# Run neutron server
exec /run-neutron.sh
