#!/usr/bin/env bash

marker_start=$1
marker_end=$2


for d in /var/log/midolman.*; do
    MIDOLMAN_LOG_FILE=$d/midolman.log
    cd $d
    [ -f $MIDOLMAN_LOG_FILE ] && {
        echo ================================
        echo $MIDOLMAN_LOG_FILE
        echo ================================

        #cat  $MIDOLMAN_LOG_FILE
        marker_start=${marker_start//\"/}
        marker_end=${marker_end//\"/}

        sed -n "/$marker_start/, /$marker_end/ p" $MIDOLMAN_LOG_FILE
    }
    cd $OLDPWD
done

exit 0 # make sure that nose plugin doesn't die

