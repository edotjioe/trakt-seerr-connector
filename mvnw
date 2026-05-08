#!/bin/sh
# Maven Wrapper — downloads Maven 3.9.9 on first run (needs Java 21 in PATH).
# https://maven.apache.org/wrapper/
set -e
WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"
if [ ! -f "$WRAPPER_JAR" ]; then
    URL=$(grep wrapperUrl "$WRAPPER_PROPERTIES" | cut -d= -f2-)
    echo "Downloading Maven Wrapper from $URL"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$WRAPPER_JAR" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$URL"
    else
        echo "ERROR: Neither curl nor wget found." && exit 1
    fi
fi
exec java -jar "$WRAPPER_JAR" "$@"
