#!/bin/bash

. venv/bin/activate

# Stop all conatiners so we don't have leaked processes
sandbox-manage -c sandbox.conf kill-all
