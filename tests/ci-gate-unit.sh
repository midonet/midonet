#!/bin/bash

./gradlew clean test cobertura -x integration
cat<<EOF > stats.sar
EOF
