#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
