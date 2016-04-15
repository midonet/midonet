#!/bin/bash

# Install the latest packages from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# Failfast if we cannot update the packages locally
apt-get install -qy --force-yes python-midonetclient/local || exit 1

# Copy specific neutron configuration for v1
cp /override/midonet.ini /etc/neutron/plugins/midonet/midonet.ini

# Point .midonetrc to correct api port (for interactive midonet-cli)
sed -i "s/8181/8080/" /root/.midonetrc

exec ./run-neutron.sh
