#!/usr/bin/env bash

echo
echo ====== zkdump
echo

ZK_NODE=${ZK_NODE:-10.0.0.2:2181}
zkdump -z $ZK_NODE -d -p

# zookeeper server logs
for d in /var/log/zookeeper*; do
    cd $d
    for f in *; do
        [ $f == "*" ] && continue
        echo
        echo ====== ZK server logs $d/$f
        echo
        cat $f
    done
    cd -
done

# cassandra server logs
for d in /var/log/cassandra*; do
    cd $d
    for f in *; do
        [ $f == "*" ] && continue
        echo
        echo ====== Cassandra server logs $d/$f
        echo
        cat $f
    done
    cd -
done

exit 0 # make sure that nose plugin doesn't die

