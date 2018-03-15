#!/bin/bash

./gradlew clean -PmaxTestForks=2 test cobertura -x integration
