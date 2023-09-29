#!/usr/bin/bash

trap 'trap - SIGTERM && kill -- -$$' SIGINT SIGTERM EXIT

if ! [ -d /tmp/otp ]; then
    mkdir /tmp/otp
    curl -L http://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf -o /tmp/otp/oregon-latest.osm.pbf
    curl -L http://developer.trimet.org/schedule/gtfs.zip -o /tmp/otp/trimet.gtfs.zip
fi

init_cache() {
    # Generate cache
    /usr/lib/jvm/java-11-openjdk/bin/java \
        -jar target/otp-2.1.0-shaded.jar \
        /tmp/otp \
        --build --save
}

run() {
    /usr/lib/jvm/java-17-openjdk/bin/java \
        `#'-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005'` \
        "$1" \
        -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
        --add-opens=java.base/java.io=ALL-UNNAMED \
        --add-opens=java.base/java.util=ALL-UNNAMED \
        -jar target/otp-2.1.0-shaded.jar \
        /tmp/otp \
        --load --serve &

    OTP_PID="$!"

    until curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432,-122.79109954833983&toPlace=45.48276208721778,-122.58716583251952&time=1:21pm&date=03-29-2023&mode=TRANSIT,WALK&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'; do
        echo "Trying curl"
        sleep 3
    done

    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432,-122.79109954833983&toPlace=45.48276208721778,-122.58716583251952&time=1:21pm&date=03-29-2023&mode=TRANSIT,WALK&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=BUS%2CWALK&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=TRAM%2CRAIL%2CSUBWAY%2CFUNICULAR%2CGONDOLA%2CWALK&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=BICYCLE&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=TRANSIT%2CBICYCLE&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=CAR_PICKUP&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=BICYCLE_PARK%2CTRANSIT&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'
    curl 'http://localhost:8080/otp/routers/default/plan?fromPlace=45.47313279401432%2C-122.79109954833983&toPlace=45.48276208721778%2C-122.58716583251952&time=1%3A21pm&date=03-29-2023&mode=FLEX_ACCESS%2CFLEX_EGRESS%2CTRANSIT&maxWalkDistance=4828.032&arriveBy=false&wheelchair=false&bannedRoutes=&optimize=TRIANGLE&triangleTimeFactor=0.4197530864197529&triangleSlopeFactor=0.27943178513846384&triangleSafetyFactor=0.3008151284417832&showIntermediateStops=true&debugItineraryFilter=false&locale=en'

    echo -e "\nKILLING $OTP_PID"
    kill "$OTP_PID"
    wait "$OTP_PID"
    sleep 1
    echo "Done"
}

rm -rf /tmp/otp/graph.obj
init_cache
echo "Cache built"

run "$1"
echo "Run done"
