#!/bin/bash

# Stop all conatiners so we don't have leaked processes
sandbox-manage -c sandbox.conf kill-all
