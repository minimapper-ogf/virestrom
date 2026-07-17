#!/bin/bash

set -euo pipefail

rm -rf bin/* dist/*
mkdir -p bin dist

# We still need a local copy of JTS just to satisfy the javac compiler
JOSM_CP="./josm-tested.jar"
JTS_JAR="./jts-core-1.19.0.jar"

if [[ -f "$JTS_JAR" ]]; then
  JOSM_CP="$JOSM_CP:$JTS_JAR"
fi

# Compile targeting Java 11
javac -classpath "$JOSM_CP" --release 11 -d bin $(find src -name "*.java")

# Package ONLY your plugin's compiled classes
jar cfm dist/virestrom-2.jar manifest.txt -C bin .

TARGET_DIR="$HOME/.var/app/org.openstreetmap.josm/data/JOSM/plugins"
mkdir -p "$TARGET_DIR"
cp dist/virestrom-2.jar "$TARGET_DIR/"

echo "Built dist/virestrom-2.jar successfully."