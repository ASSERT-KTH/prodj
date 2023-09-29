#!/usr/bin/bash

trap 'trap - SIGTERM && kill -- -$$' SIGINT SIGTERM EXIT

curl_request() {
    echo -n "$1: '$2' "
    curl -i -s -k -L --data "$2" "https://localhost:8443/$1" -b cookies --cookie-jar cookies -w "%{http_code}\n" -o /dev/null
}

requests() {
    echo "Starting workload"
    CSRF="$(curl -Ssk https://localhost:8443/ --cookie-jar cookies | grep ' name="csrfToken" ' | head -n 1 | grep -oP '(?<=value=").+(?=")')"
    echo "CSRF is '$CSRF'"
    curl_request 'cart/add' "productId=12&quantity=1&csrfToken=$CSRF"
    curl_request 'cart/add' "productId=9&quantity=1&csrfToken=$CSRF"
    curl_request 'cart/add' "productId=6&quantity=1&csrfToken=$CSRF"
    curl_request 'register' "redirectUrl=&customer.emailAddress=Admin%40example.org&customer.firstName=Admin&customer.lastName=Admin&password=Admin123&passwordConfirm=Admin123&csrfToken=$CSRF"
    curl_request 'account/wishlist/add' "productId=3&quantity=1&csrfToken=$CSRF"
    curl_request 'cart/add' "productId=3&quantity=1&csrfToken=$CSRF"

    curl_request 'cart/updateQuantity' "productId=9&skuId=9&orderItemId=2&quantity=10&csrfToken=$CSRF"

    curl_request 'checkout/singleship' \
       "address.isoCountryAlpha2=US&address.fullName=Admin&address.addressLine1=Admin&address.addressLine2=Admin&address.city=Admin&address.stateProvinceRegion=WA&address.postalCode=Admin&address.phonePrimary.phoneNumber=Admin&saveAsDefault=false&fulfillmentOptionId=2&csrfToken=$CSRF"

    curl_request 'checkout/payment' \
       "paymentToken=4111111111111111%23Hotsauce+Connoisseur%2301%2F99%23123&customerPaymentId=&shouldUseCustomerPayment=false&shouldSaveNewPayment=true&shouldUseShippingAddress=true&_shouldUseShippingAddress=on&address.isoCountryAlpha2=US&address.fullName=&address.addressLine1=&address.addressLine2=&address.city=&address.stateProvinceRegion=&address.postalCode=&address.phonePrimary=&emailAddress=Admin%40example.org&csrfToken=$CSRF" \
    curl_request 'checkout/complete' "payment_method_nonce=&csrfToken=$CSRF"

    echo "Workload done"
    sleep 1
}

run() {
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
        mvn spring-boot:run \
        -Dboot.jvm.args="--add-opens java.base/java.util=ALL-UNNAMED -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 $1" \
        &

    BROADLEAF="$!"

    until curl -k 'http://localhost:8443'; do
        echo "Trying curl"
        sleep 3
    done

    echo "Service up."
    requests

    echo -e "\nKILLING $BROADLEAF"
    kill "$BROADLEAF"
    wait "$BROADLEAF"
    sleep 1
    echo "Done"
}

cd ../BroadleafDemoSite/site || exit 1

rm -f cookies

run "$1"
