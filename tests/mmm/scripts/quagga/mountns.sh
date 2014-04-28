#! /bin/sh

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi
mount --bind /var/log/quagga.$n /var/log/quagga
mount --bind /etc/quagga.$n /etc/quagga

exec $*
