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

# apt package pinning (zookeeper 3.4.5, ovs-dp 1.10)
apt_extra="/etc/apt/sources.list.d"
raring="$ubuntu_archive raring universe"
saucy="$ubuntu_archive saucy universe"
echo -e "deb $raring\ndeb-src $raring" \ | sudo tee $apt_extra/raring.list
echo -e "deb $saucy\ndeb-src $saucy" \ | sudo tee $apt_extra/saucy.list
sudo cp /midonet/vagrant/01ubuntu /etc/apt/apt.conf.d/
sudo cp /midonet/vagrant/preferences /etc/apt/
sudo apt-get update

# packages installation
sudo apt-get install -y git vim tree screen curl openjdk-7-jdk
sudo apt-get install -y openvswitch-datapath-dkms linux-headers-`uname -r`
sudo modprobe openvswitch
sudo apt-get install -y zookeeper zookeeperd
sudo service zookeeper stop

# manual package installation and configuration
/home/vagrant/apache-maven-$mvn_ver/bin/mvn -v > /dev/null 2>&1 || {
  curl $mvn_dl | tar -xz
  echo "export PATH=\$PATH:\$HOME/apache-maven-$mvn_ver/bin" >> /home/vagrant/.bashrc
}

/home/vagrant/zinc-$zinc_ver/bin/zinc -version >/dev/null 2>&1 || {
  curl $zinc_dl | tar -xz
  echo "export PATH=\$PATH:\$HOME/zinc-$zinc_ver/bin" >> /home/vagrant/.bashrc
  /home/vagrant/zinc-$zinc_ver/bin/zinc -start
}

# mm-*ctl PATH configuration
echo "export PATH=\$PATH:/midonet/midolman/src/deb/bin" >> /home/vagrant/.bashrc

exit 0

# cassandra installation (not tested yet)
cass_repo='deb http://www.apache.org/dist/cassandra/debian 11x main\ndeb-src http://www.apache.org/dist/cassandra/debian 11x main'
echo -e $cass_repo | sudo tee /etc/apt/sources.list.d/cassandra.list
sudo gpg --keyserver pgp.mit.edu --recv-keys F758CE318D77295D
sudo gpg --export --armor F758CE318D77295D | sudo apt-key add -
sudo gpg --keyserver pgp.mit.edu --recv-keys 2B5C1B00
sudo gpg --export --armor 2B5C1B00 | sudo apt-key add -

sudo apt-get udpate
sudo apt-get install -y cassandra
sudo service cassandra stop

sudo chown cassandra:cassandra /var/lib/cassandra
sudo rm -rf /var/lib/cassandra/data/system/LocationInfo
CASSANDRA_FILE='/etc/cassandra/cassandra.yaml'
sudo sed -i -e "s/^cluster_name:.*$/cluster_name: \'midonet\'/g" $CASSANDRA_FILE
CASSANDRA_ENV_FILE='/etc/cassandra/cassandra-env.sh'
sudo sed -i 's/#\(MAX_HEAP_SIZE=\).*$/\1128M/' $CASSANDRA_ENV_FILE
sudo sed -i 's/#\(HEAP_NEWSIZE=\).*$/\164M/' $CASSANDRA_ENV_FILE
