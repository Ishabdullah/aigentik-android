#!/bin/sh
GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    gradle wrapper --gradle-version 8.9
fi

exec gradle "$@"
