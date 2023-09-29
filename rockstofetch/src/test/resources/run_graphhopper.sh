#!/usr/bin/bash

trap 'trap - SIGTERM && kill -- -$$' SIGINT SIGTERM EXIT

run() {
    /usr/lib/jvm/java-17-openjdk/bin/java \
        `#'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005'` \
        "$1" \
        -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
        -Ddw.graphhopper.datareader.file=berlin-latest.osm.pbf \
        -jar web/target/graphhopper-web-*.jar server config-example.yml &

    GRAPHHOPPER="$!"

    until curl 'http://localhost:8989/route?point=52.565499,13.469925&point=52.508281,13.326416&type=json&locale=en-US&key=&elevation=false&profile=car'; do
        echo "Trying curl"
        sleep 3
    done

    echo -e "\nKILLING $GRAPHHOPPER"
    kill "$GRAPHHOPPER"
    wait "$GRAPHHOPPER"
    sleep 1
    echo "Done"
}

rm -rf graph-cache logs

run "-Dfoo"
echo "FIRST DONE"
run "$1"
echo "SECOND DONE"
