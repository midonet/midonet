#!/bin/bash
DIR=$(dirname $0)
PYTHONPATH=tests python $DIR/ssh-containers-from-docker.py | \
    sed 's/..python.unicode //g' > ssh-containers.yaml
