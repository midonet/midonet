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

    head -n 200 $MIDOLMAN_LOG_FILE
    echo
    echo ...
    echo

    # Remove leading and training double quotes
    marker_start=${marker_start//\"/}
    marker_end=${marker_end//\"/}

    sed -n "/$marker_start/, /$marker_end/ p" $MIDOLMAN_LOG_FILE
}

DPCTL_TIMEOUT_IN_SEC=10

SHOW_DP_CMDLINE="mm-dpctl --timeout $DPCTL_TIMEOUT_IN_SEC --show-dp midonet"
echo
echo ====== DP status: $SHOW_DP_CMDLINE
echo

$SHOW_DP_CMDLINE


DUMP_DP_CMDLINE="mm-dpctl --timeout $DPCTL_TIMEOUT_IN_SEC --dump-dp midonet"
echo
echo ====== flow dump: $DUMP_DP_CMDLINE
echo

$DUMP_DP_CMDLINE

exit 0 # make sure that nose plugin doesn't die
