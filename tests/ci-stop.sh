#!/bin/bash

if [ -f /opt/midonet-venv/bin/activate ]; then                                                                          
    . /opt/midonet-venv/bin/activate
else
    . venv/bin/activate
fi

# Stop all conatiners so we don't have leaked processes
sandbox-manage -c sandbox.conf kill-all
