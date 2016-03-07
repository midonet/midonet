#! /bin/sh

ENV_SH=$1
shift
echo Sourcing ${ENV_SH}
. ${ENV_SH}
exec java $JVM_OPTS "$@"
