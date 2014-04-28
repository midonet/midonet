#! /bin/sh

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi
mount --bind /var/lib/zookeeper.$n /var/lib/zookeeper
mount --bind /var/log/zookeeper.$n /var/log/zookeeper
mount --bind /etc/zookeeper.$n /etc/zookeeper

exec $*
