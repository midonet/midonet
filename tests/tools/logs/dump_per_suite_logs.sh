#!/usr/bin/env bash

echo ==============
echo per suite logs
echo ==============

# zookeeper server logs
for d in /var/log/zookeeper*; do
    cd $d
    for f in *; do
        [ $f == "*" ] && continue
        echo ==============
        echo ZK server logs $d/$f
        echo ==============
        cat $f
    done
    cd $OLDPWD
done

# cassandra server logs
for d in /var/log/cassandra*; do
    cd $d
    for f in *; do
        [ $f == "*" ] && continue
        echo =====================
        echo Cassandra server logs $d/$f
        echo =====================
        cat $f
    done
    cd $OLDPWD
done

# MidoNet API log
MIDONET_API_LOGFILE=/var/log/tomcat[67]/midonet-api.log
[ -f $MIDONET_API_LOGFILE ] && {
    echo ======================
    echo MidoNet API server log: $MIDONET_API_LOGFILE
    echo ======================
    cat  $MIDONET_API_LOGFILE
}


MIDOLMAN_LOG_FILE=midolman.log
MIDOLMAN_UPSTART_ERR_LOG_FILE=upstart-stderr.log
for d in /var/log/midolman.*; do
    cd $d
    [ -f $MIDOLMAN_UPSTART_ERR_LOG_FILE ] && {
        echo
        echo ================================
        echo $d/$MIDOLMAN_UPSTART_ERR_LOG_FILE
        echo ================================
        cat $MIDOLMAN_UPSTART_ERR_LOG_FILE
    }

    [ -f $MIDOLMAN_LOG_FILE ] && {
        echo ================================
        echo $d/$MIDOLMAN_LOG_FILE
        echo ================================

        cat  $MIDOLMAN_LOG_FILE
    }
    cd $OLDPWD
done

exit 0 # make sure that nose plugin doesn't die

