#!/bin/bash

OPTIND=1
while getopts ":b:c:I:" opt; do
    case $opt in
    b)
        BUILD=$OPTARG
        ;;
    c)
        COMMITISH=$OPTARG
        ;;
    I)
        INFLUX_ENDPOINT=$OPTARG
        ;;
    esac
done

if [ -z "$COMMITISH" -o -z "$INFLUX_ENDPOINT" ]; then
    echo "Usage: ci-perf.sh -c COMMITISH -I INFLUX_ENDPOINT -b BUILD"
    exit 1
fi

if [ -z "$BUILD" ]; then
    BUILD="unknown"
fi

source venv/bin/activate

# disable debug logging
export MIDOLMAN1=$(docker ps -q -a -f=name=midolman1)
echo "agent.loggers.root=INFO" | docker exec -i $MIDOLMAN1 mn-conf set -t default

cd tests/mdts/tests/performance_tests/
./run_tests.sh -r $WORKSPACE/tests -l logs
if [ $? = 0 ]; then
    export JMXTRANS=$(docker ps -q -a -f=name=jmxtrans)
    docker exec $JMXTRANS /usr/bin/upload_stats -b $BUILD -c $COMMITISH -I $INFLUX_ENDPOINT

    export MIDOLMAN1=$(docker ps -q -a -f=name=midolman1)
    docker exec $MIDOLMAN1 /usr/local/bin/upload_mem_stats -b $BUILD -c $COMMITISH -I $INFLUX_ENDPOINT

    export MIDOLMAN2=$(docker ps -q -a -f=name=midolman2)
    docker exec $MIDOLMAN2 /usr/local/bin/upload_mem_stats -b $BUILD -c $COMMITISH -I $INFLUX_ENDPOINT
else
    echo "Tests failed, skipping stats upload"
fi
cd -

cd /tmp/jfr
tar -cvzf $WORKSPACE/perf_jfr.tar.gz *
cd -
