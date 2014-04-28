#! /bin/sh

conf_file="$(pwd)/jmxtrans.conf"

cd /usr/share/jmxtrans && CONF_FILE="$conf_file" bash jmxtrans.sh stop
