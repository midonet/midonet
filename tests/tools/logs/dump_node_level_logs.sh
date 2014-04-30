#!/usr/bin/env bash

marker_start=${1:-'.*'}
marker_end=${2:-'.*'}

MIDOLMAN_LOG_DIR=${MIDOLMAN_LOG_DIR:-/var/log/midolman/}

echo
echo ====== Midolman Logs: $MIDOLMAN_LOG_DIR
echo


NUM_LOG_LINES=${NUM_LOG_LINES:-2000}
MIDOLMAN_LOG_FILE=$MIDOLMAN_LOG_DIR/midolman.log
MIDOLMAN_UPSTART_ERR_LOG_FILE=$MIDOLMAN_LOG_DIR/upstart-stderr.log


[ -f $MIDOLMAN_UPSTART_ERR_LOG_FILE ] && {
    echo
    echo "== " $MIDOLMAN_UPSTART_ERR_LOG_FILE
    echo
    cat $MIDOLMAN_UPSTART_ERR_LOG_FILE
}

[ -f $MIDOLMAN_LOG_FILE ] && {
    echo
    echo "== " $MIDOLMAN_LOG_FILE
    echo

    cat  $MIDOLMAN_LOG_FILE
}

echo
echo ====== DP status: mm-dpctl --show-dp midonet
echo

mm-dpctl --show-dp midonet


echo
echo ====== flow dump: mm-dpctl --dump-dp midonet
echo

mm-dpctl --dump-dp midonet

exit 0 # make sure that nose plugin doesn't die
