#!/usr/bin/env bash

set -x

sudo mv /usr/share/man/man1/mn-conf.1.gz /usr/share/man/man1/mn-conf.1.gz.old
sudo mv /usr/bin/mm-meter /usr/bin/mm-meter.old
sudo mv /usr/bin/mm-stat /usr/bin/mm-stat.old
sudo mv /usr/bin/mn-conf /usr/bin/mn-conf.old

ART_URL="http://artifactory.bcn.midokura.com/artifactory/midonet-5-deb"
echo "deb $ART_URL stable main" > midokura.5.list
sudo mv midokura.5.list /etc/apt/sources.list.d/
sudo apt-get update
sudo apt-get -o Dpkg::Options::="--force-overwrite" install midonet-cluster midonet-tools

echo "#deb $ART_URL stable main" > midokura.5.list
sudo mv midokura.5.list /etc/apt/sources.list.d/
sudo apt-get update

sudo mv /usr/share/man/man1/mn-conf.1.gz /usr/share/man/man1/mn-conf.1.gz.new
sudo mv /usr/bin/mm-meter /usr/bin/mm-meter.new
sudo mv /usr/bin/mm-stat /usr/bin/mm-stat.new
sudo mv /usr/bin/mn-conf /usr/bin/mn-conf.new

sudo mv /usr/share/man/man1/mn-conf.1.gz.old /usr/share/man/man1/mn-conf.1.gz
sudo mv /usr/bin/mm-meter.old /usr/bin/mm-meter
sudo mv /usr/bin/mm-stat.old /usr/bin/mm-stat
sudo mv /usr/bin/mn-conf.old /usr/bin/mn-conf

