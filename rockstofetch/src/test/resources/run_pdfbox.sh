#!/usr/bin/bash

trap 'trap - SIGTERM && kill -- -$$' SIGINT SIGTERM EXIT

run() {
    /usr/lib/jvm/java-17-openjdk/bin/java \
        `#'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005'` \
        -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
        "$1" \
        -jar app/target/pdfbox-app-3.0.0-RC1.jar "${@:2}"
}

rm -f CodeMonkey-*.pdf CodeMonkey*.jpg Merged.pdf

echo "Running"
#run "$1" export:text --input=CodeMonkey.pdf --output=/dev/null
#run "$1" split -split=1 --input=CodeMonkey.pdf
#run "$1" merge -i $(find -iname 'CodeMonkey-*.pdf' | cat | tr '\n' ' ' | sed -E 's/ (\S)/ -i \1/g') --output=Merged.pdf
run "$1" render --input=CodeMonkey.pdf
echo "Done"
