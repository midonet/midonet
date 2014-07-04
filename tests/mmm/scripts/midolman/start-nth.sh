#! /bin/sh

find /var/log/quagga -type f -exec rm -f '{}' ';'

sysctl -w net.ipv6.conf.default.disable_ipv6=1

exec /usr/share/midolman/midolman-start
