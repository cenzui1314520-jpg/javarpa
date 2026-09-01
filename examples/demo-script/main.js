// 云端下发的示例脚本：打开系统计算器计算 1 + 2 =
// 可用任务参数覆盖包名: {"pkg": "com.android.calculator2"}
log("demo 脚本启动, 参数:", JSON.stringify(params));
var pkg = params.pkg || "com.android.calculator2";
if (!auto.launch(pkg)) {
  log("未找到计算器应用，请在屏幕上手动打开任意页面后重试");
}
sleep(2500);
auto.waitIfPaused();

var keys = ["1", "+", "2", "="];
keys.forEach(function (k) {
  var hit = auto.text(k).findOne(3000);
  if (hit) {
    hit.click();
    auto.report.ok();
    log("已点击按键 " + k);
  } else {
    auto.report.fail();
    log("未找到按键 " + k);
  }
  sleep(300);
});

log("demo 脚本执行完成, 成功 " + auto.report.getOk() + " 次, 失败 " + auto.report.getFail() + " 次");
toast("脚本执行完成");
