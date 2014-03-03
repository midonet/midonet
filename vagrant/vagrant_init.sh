#! /bin/bash

set -x

# package versions
mvn_ver="3.0.5"
zinc_ver="0.2.5"

# download urls
ubuntu_archive="http://jp.archive.ubuntu.com/ubuntu/"
mvn_dl="http://ftp.tsukuba.wide.ad.jp/software/apache/"\
"maven/maven-3/$mvn_ver/binaries/apache-maven-$mvn_ver-bin.tar.gz"
zinc_dl="http://repo.typesafe.com/typesafe/"\
"zinc/com/typesafe/zinc/dist/$zinc_ver/zinc-$zinc_ver.tgz"
jmxtrans_dl="http://cloud.github.com/downloads/jmxtrans/jmxtrans/"\
"jmxtrans_20121016-175251-ab6cfd36e3-1_all.deb"
mido_apt_url="http://suzuki:ironchef@apt.midokura.com"

# some default conf values
jmxtrans_jvmheap="jmxtrans jmxtrans/jvm_heap_size string 256"

cass_conf="/etc/cassandra/cassandra.yaml"
cass_env='/etc/cassandra/cassandra-env.sh'
cass_heap_max="1128M"
cass_heap_new="164M"

# pre install basic package installation
sudo apt-get install -y curl

# apt configuration

# apt package pinning (zookeeper 3.4.5, ovs-dp 1.10, quagga 0.99.22)
apt_extra="/etc/apt/sources.list.d"
raring="$ubuntu_archive raring main universe"
saucy="$ubuntu_archive saucy universe"
echo -e "deb $raring\ndeb-src $raring" | sudo tee $apt_extra/raring.list
echo -e "deb $saucy\ndeb-src $saucy" | sudo tee $apt_extra/saucy.list
sudo cp /midonet/vagrant/01ubuntu /etc/apt/apt.conf.d/
sudo cp /midonet/vagrant/preferences /etc/apt/

# apt sources for cassandra
cass_src="http://www.apache.org/dist/cassandra/debian 11x main"
echo -e "deb $cass_src\ndeb-src $cass_src" \ | sudo tee $apt_extra/cassandra.list
sudo gpg --keyserver pgp.mit.edu --recv-keys F758CE318D77295D
sudo gpg --export --armor F758CE318D77295D | sudo apt-key add -
sudo gpg --keyserver pgp.mit.edu --recv-keys 2B5C1B00
sudo gpg --export --armor 2B5C1B00 | sudo apt-key add -

# add midonet apt source for python midonetclient
mido_apt="deb [arch=amd64] $mido_apt_url/midonet/v1.3/test precise main non-free"
echo $mido_apt | sudo tee $apt_extra/midokura.list
curl -k $mido_apt_url/packages.midokura.key | sudo apt-key add -

sudo apt-get update


# packages installation

sudo apt-get install -y git vim tree screen openjdk-7-jdk
sudo apt-get install -y bridge-utils quagga python-httplib2 python-midonetclient
sudo apt-get install -y nmap mz rrdtool

sudo apt-get install -y openvswitch-datapath-dkms linux-headers-`uname -r`
sudo modprobe openvswitch

sudo apt-get install -y zookeeper zookeeperd

sudo apt-get -y --force-yes install cassandra
sudo update-rc.d cassandra enable
sudo sed -i -e "s/^cluster_name:.*$/cluster_name: \'midonet\'/g" $cass_conf
sudo sed -i "1i MAX_HEAP_SIZE="$cass_heap_max $cass_env
sudo sed -i "1i HEAP_NEWSIZE="$cass_heap_new $cass_env
sudo sed -i "s/Xss\([0-9]*\)k/Xss512k/" $cass_env

sudo service cassandra stop
sudo rm -rf /var/lib/cassandra/*
sudo bash -c "echo $(hostname|sed 's/ip-//;s/-/./g') $(hostname -f) $(hostname) >>/etc/hosts"

sudo apt-get install -y tomcat7


# manual packages installation and configuration

/home/vagrant/apache-maven-$mvn_ver/bin/mvn -v > /dev/null 2>&1 || {
  curl $mvn_dl | tar -xz
  echo "export PATH=\$PATH:\$HOME/apache-maven-$mvn_ver/bin" >> /home/vagrant/.bashrc
}

/home/vagrant/zinc-$zinc_ver/bin/zinc -version >/dev/null 2>&1 || {
  curl $zinc_dl | tar -xz
  echo "export PATH=\$PATH:\$HOME/zinc-$zinc_ver/bin" >> /home/vagrant/.bashrc
}

/usr/share/jmxtrans/jmxtrans.sh stop >/dev/null 2>&1 || {
  echo $jmxtrans_jvmheap | sudo debconf-set-selections
  curl $jmxtrans_dl > jmx_trans.deb
  sudo dpkg -i jmx_trans.deb
  rm jmx_trans.deb
}

# mm-*ctl PATH configuration
echo "export PATH=\$PATH:/midonet/midolman/src/deb/bin" >> /home/vagrant/.bashrc


# restart services
sudo service tomcat7 restart
sudo service zookeeper restart
sudo service cassandra start
/home/vagrant/zinc-$zinc_ver/bin/zinc -start
