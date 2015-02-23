#!/bin/bash  -x
# vim: ts=2 ss=2 ff=unix tw=72
#
# This script simplifies configuring a VTEP emulator, and adds some
# utilities to manage it. It's intended to be used for manual tests of
# the vxgw feature.
#
# Example:
#
# We will here configure a VTEP on 192.168.2.4 as management and tunnel
# ip, that has a local port called in6 in it, and a namespace plugged to
# it with IP 10.0.2.4/24 on vlan 0:
#
#   $ ./vtep.sh config vtep0 192.168.2.4 192.168.2.4
#   $ ./vtep.sh add_port vtep0 in6 10.0.2.4/24
#   $ ./vtep.sh reload vtep0
#
# You can easily add new ports with:
#
#   $ ./vtep.sh add_port vtep0 someport 10.0.2.7/24 5
#
# This one was on vlan 5
#

# Output an error, and exit
function err {
  echo "ERROR: $1"
  exit -1
}

# Display the usage, and exit
function usage {
  cat <<EOF
Usage:
  - Configure: $0 conf <vtep_name> <tunnel_ip> <management_ip>
  - Clean: $0 clean
  - Reload: $0  reload <vtep_name>
    - Restarts services, and reloads emulator for the given vtep
  - Add port: $0 add_port <vtep_name> <port_name> <ip> [vlan]
    - Creates a new port on the vtep. Associates a new veth pair
      one of which ends is inside a namespace called ns<port_name>
      with the given ip and vlan (if given)
EOF
  exit -1
}

# Verify that all dependencies are met
function check_deps {
  which ovsdb-client > /dev/null|| err "OVSDB doesn't seem to be installed. This is unexpected"
  which ovs-vsctl > /dev/null || err "openvswitch-switch doesn't seem to be installed. This is unexpected"
  which vtep-ctl > /dev/null || err "openvswitch-vtep doesn't seem to be installed. This is unexpected"
}

# Clean slate the VTEP config
function clean {
  echo "Warning, this will remove all namespaces and wipe off the OVSDB, continue?"
  select want in Yes No ; do
    if [ "$want" == "Yes" ]
    then
      break
    else
      exit -1
    fi
  done

  ip netns | while read ns ; do
    echo "Deleting namespace $ns"
    ip netns del $ns
  done

  rm /etc/openvswitch/vtep.db
  rm /etc/openvswitch/conf.db
  /etc/init.d/openvswitch-switch restart
  /etc/init.d/openvswitch-vtep restart
}

# Configure a clean vtep
function config {
  if [ $# -lt 3 ]
  then
    usage
  fi
  vtep=$1 ; shift
  tunIp=$1 ; shift
  mgmtIp=$1 ; shift

  ovs-vsctl add-br $vtep
  vtep-ctl add-ps $vtep
  vtep-ctl set Physical_Switch $vtep tunnel_ips=$tunIp
  vtep-ctl set Physical_Switch $vtep management_ips=$mgmtIp
}

# Add a port to the VTEP, associating a namespace with a veth pair
function add_port {
  if [ $# -lt 3 ]
  then
    usage
  fi
  vtep=$1
  port=$2
  ip=$3
  vlan=$4
  ip netns add "ns$port"
  ip link add "${port}.in" type veth peer name "$port"
  ip link set up dev "$port"
  ip link set "${port}.in" netns "ns$port"
  ip netns exec "ns$port" ip link set up dev "${port}.in"
  if [ "$vlan" == "" ]
  then
    ip netns exec ns$port ifconfig ${port}.in $ip up
  else
    ip netns exec ns$port ip link add link ${port}.in name ${port}.in.$vlan type vlan id $vlan
    ip netns exec ns$port ifconfig ${port}.in.${vlan} $ip up
  fi
  ip netns exec "ns$port" ifconfig lo up
  ovs-vsctl add-port $vtep $port
  vtep-ctl add-port $vtep $port
  echo "Created new port $port with IP $ip on vlan $vlan"
}

function do_reload {
  if [ "$1" == "" ]
  then
    usage
  fi
  /etc/init.d/openvswitch-switch restart
  /etc/init.d/openvswitch-vtep restart
  killall ovs-vtep
  sleep 3
  /usr/share/openvswitch/scripts/ovs-vtep --log-file=/var/log/openvswitch/ovs-vtep.log --pidfile=/var/run/openvswitch/ovs-vtep.pid --detach $1
}

check_deps

if [ `whoami` != "root" ] ;
then
  err "Please run this as root"
fi

cmd=$1 ; shift
case $cmd in
  config) config $* ;;
  clean) clean ;;
  add_port) add_port $* ;;
  reload) do_reload $* ;;
  *) echo unknown command $cmd ; usage ;;
esac
