#!/bin/bash

./gradlew --no-daemon clean test cobertura -x integration --debug --stacktrace
