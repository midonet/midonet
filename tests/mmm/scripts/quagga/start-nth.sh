#! /bin/sh

n=$1

find /var/log/quagga.$n -type f -exec rm -f '{}' ';'

if test -x /etc/init.d/zebra; then
    /etc/rc.d/init.d/zebra start
else
    /etc/init.d/quagga start
fi

if test -x /etc/rc.d/init.d/bgpd; then
    /etc/rc.d/init.d/bgpd start
fi
