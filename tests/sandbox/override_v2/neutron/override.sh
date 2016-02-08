#!/bin/bash

# FIXME: We get the plugin from internal artifactory (through its private IP) 
# to run vpnaas for kilo.
# The package should come as a parameter in the CI (but this needs some work).
# This should be removed once the pipeline passes this package for testing.
REPO_URL="http://192.168.30.4/artifactory/"
PACKAGE_DIST="openstack-kilo-deb/deb/unstable/"
PACKAGE_NAME="python-neutron-plugin-midonet_2015.1.1-2_all.deb"
mkdir /packages-neutron
wget -O /packages-neutron/$PACKAGE_NAME $REPO_URL$PACKAGE_DIST$PACKAGE_NAME
# Update the local repo
apt-get update && apt-get -qy install --no-install-recommends dpkg-dev
(cd /packages-neutron; \
 dpkg-scanpackages . /dev/null | gzip -9c > Packages.gz; \
 apt-ftparchive -o APT::FTPArchive::Release::Suite="local" release . > Release;)

# Install dependencies (advanced neutron services)
apt-get -qy install python-neutron-vpnaas python-neutron-fwaas python-neutron-lbaas

# Install the latest from local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
echo "deb file:/packages-neutron /" >> $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# This only works for kilo, liberty name changed to python-networking-midonet
apt-get install -qy --force-yes python-neutron-plugin-midonet/local \
                                python-midonetclient/local

cp /override/midonet.ini /etc/neutron/plugins/midonet/midonet.ini

# Run neutron server
exec /run-neutron.sh
