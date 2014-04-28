#! /bin/sh

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi
mount --bind /var/lib/cassandra.$n /var/lib/cassandra
mount --bind /var/log/cassandra.$n /var/log/cassandra
mount --bind /etc/cassandra.$n /etc/cassandra

exec $*
