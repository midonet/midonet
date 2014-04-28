#! /bin/sh

find /var/log/zookeeper -type f -exec rm -f '{}' ';'

/etc/init.d/zookeeper start
