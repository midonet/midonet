#! /bin/sh

find /var/log/cassandra -type f -exec rm -f '{}' ';'

MAX_HEAP_SIZE="128M" HEAP_NEWSIZE="64M" /etc/init.d/cassandra start
