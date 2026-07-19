#!/bin/sh
set -eu

tool_name=${1:?Android tool name is required}
request_body=${2:?Android tool request JSON is required}
proxy_url=${OPENHOUSE_ANDROID_BRIDGE_URL:?Android Bridge proxy URL is missing}

case "$proxy_url" in
    http://127.0.0.1:*/*|http://\[::1\]:*/*) ;;
    *)
        echo "Android Bridge proxy URL is not loopback" >&2
        exit 78
        ;;
esac

case "$tool_name" in
    clipboard|intent|share|notification) ;;
    *)
        echo "Unsupported Android tool: $tool_name" >&2
        exit 64
        ;;
esac

exec curl \
    --silent \
    --show-error \
    --max-time 60 \
    --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "$request_body" \
    --write-out '\n%{http_code}' \
    "$proxy_url/v1/tools/$tool_name"
