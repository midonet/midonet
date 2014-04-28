#! /bin/sh

rm -rf /var/log/jmxtrans/midonet-perftests

conf_file="$(pwd)/jmxtrans.conf"

cd /usr/share/jmxtrans && CONF_FILE="$conf_file" bash jmxtrans.sh start
