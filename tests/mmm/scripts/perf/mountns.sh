#! /bin/sh

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi

mount --bind /var/log/jmxtrans.$n /var/log/jmxtrans

exec $*
