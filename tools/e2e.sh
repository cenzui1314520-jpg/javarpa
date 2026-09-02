#!/usr/bin/env bash
# JavaRPA end-to-end test: admin login -> device/script/task setup -> mock device run.
set -e
BASE=http://localhost:8080
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="python3 -c"
ADMIN_PASSWORD="${E2E_PASSWORD:-admin123}"
MOCK_PID=""

say() { echo; echo "===== $* ====="; }

# 中途失败也清理 mock 进程，避免残留
trap '[ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null' EXIT

api() { # method path token [json]
  local m=$1 p=$2 t=$3 body=$4
  if [ -n "$body" ]; then
    curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$body"
  elif [ "$m" != "GET" ]; then
    curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  else
    curl -s "$BASE$p" -H "Authorization: Bearer $t"
  fi
}

say "1. admin login"
LOGIN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}")
TOKEN=$($PY "import json,sys;print(json.loads(sys.argv[1])['data']['token'])" "$LOGIN")
echo "token ok: ${TOKEN:0:20}..."

say "1.5 cleanup previous e2e data"
for tid in $(api GET /tasks "$TOKEN" | $PY "import json,sys;print(' '.join(str(t['id']) for t in json.load(sys.stdin)['data']))"); do
  api DELETE "/tasks/$tid" "$TOKEN" > /dev/null
done
for sid in $(api GET /scripts "$TOKEN" | $PY "import json,sys;print(' '.join(str(s['id']) for s in json.load(sys.stdin)['data'] if s['pkgName']=='demo.calculator'))"); do
  api DELETE "/scripts/$sid" "$TOKEN" > /dev/null
done
for did in $(api GET "/devices/page?keyword=MOCK&size=100" "$TOKEN" | $PY "import json,sys;print(' '.join(str(d['id']) for d in json.load(sys.stdin)['data']['list']))"); do
  api DELETE "/devices/$did" "$TOKEN" > /dev/null
done
echo "cleaned"

say "2. create device MOCK-001"
DEV=$(api POST /devices "$TOKEN" '{"deviceSn":"MOCK-001","name":"模拟设备一号"}')
DEVICE_ID=$($PY "import json,sys;print(json.loads(sys.argv[1])['data']['id'])" "$DEV")
SECRET=$($PY "import json,sys;print(json.loads(sys.argv[1])['data']['secret'])" "$DEV")
echo "deviceId=$DEVICE_ID secret=${SECRET:0:8}..."

say "3. create script + upload version 1 + publish ALL"
SCRIPT=$(api POST /scripts "$TOKEN" '{"name":"演示脚本","pkgName":"demo.calculator","description":"打开计算器计算1+2"}')
SCRIPT_ID=$($PY "import json,sys;print(json.loads(sys.argv[1])['data']['id'])" "$SCRIPT")
echo "scriptId=$SCRIPT_ID"
cd "$ROOT/examples/demo-script" && rm -f /tmp/demo-v1.zip && zip -q -r /tmp/demo-v1.zip main.js config.json && cd - > /dev/null
UP=$(curl -s -X POST "$BASE/scripts/$SCRIPT_ID/versions" -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/demo-v1.zip" -F "versionCode=1" -F "versionName=1.0.0" -F "changelog=first release")
echo "upload: $UP"
api POST "/scripts/$SCRIPT_ID/publish" "$TOKEN" "{\"versionCode\":1,\"targetType\":\"ALL\"}"
echo "published"

say "4. start mock device (waits for commands, runs in background)"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for cand in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
              /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
              /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$cand/bin/java" ]; then export JAVA_HOME="$cand"; break; fi
  done
fi
"$JAVA_HOME/bin/java" "$ROOT/tools/mock-device/MockDevice.java" http://localhost:8080 MOCK-001 "$SECRET" > /tmp/mock-device.log 2>&1 &
MOCK_PID=$!
sleep 6
echo "--- mock device log ---"
cat /tmp/mock-device.log

say "5. create task and start it"
TASK=$(api POST /tasks "$TOKEN" "{\"name\":\"演示任务\",\"scriptId\":$SCRIPT_ID,\"scheduleType\":\"IMMEDIATE\",\"maxRetries\":1,\"paramsJson\":\"{\\\"pkg\\\":\\\"com.android.calculator2\\\"}\",\"deviceIds\":[$DEVICE_ID]}")
TASK_ID=$($PY "import json,sys;print(json.loads(sys.argv[1])['data']['id'])" "$TASK")
echo "taskId=$TASK_ID"
api POST "/tasks/$TASK_ID/actions" "$TOKEN" '{"action":"start"}'
echo "started, waiting for device to finish..."
sleep 8
echo "--- mock device log ---"
tail -12 /tmp/mock-device.log

say "6. verify task detail / logs / stats"
DETAIL=$(api GET "/tasks/$TASK_ID" "$TOKEN")
echo "$DETAIL" | $PY "import json,sys;d=json.loads(sys.argv[1])['data'];td=d['taskDevices'][0];print('taskDevice:',td['status'],'success=',td['successCount'],'fail=',td['failCount']);print('executions:',len(d['executions']),d['executions'][0]['status'] if d['executions'] else '-')" "$DETAIL"
LOGS=$(api GET "/logs?deviceId=$DEVICE_ID&size=5" "$TOKEN")
echo "$LOGS" | $PY "import json,sys;d=json.loads(sys.argv[1])['data'];print('log count:',d['total']);[print(' LOG:',l['level'],l['content']) for l in d['list'][:3]]" "$LOGS"
STATS=$(api GET /stats/summary "$TOKEN")
echo "$STATS" | $PY "import json,sys;d=json.loads(sys.argv[1])['data'];print('devices:',d['deviceOnline'],'/',d['deviceTotal'],'todayExec:',d['todayExecTotal'],'successRate:',d['todaySuccessRate'])" "$STATS"

say "7. device page shows online"
api GET "/devices/page?keyword=MOCK" "$TOKEN" | $PY "import json,sys;d=json.load(sys.stdin)['data']['list'][0];print(d['deviceSn'],'online=',d['online'],'model=',d['model'],'engine=',d['engineVersion'])"

kill $MOCK_PID 2>/dev/null || true
echo
echo "E2E DONE"
