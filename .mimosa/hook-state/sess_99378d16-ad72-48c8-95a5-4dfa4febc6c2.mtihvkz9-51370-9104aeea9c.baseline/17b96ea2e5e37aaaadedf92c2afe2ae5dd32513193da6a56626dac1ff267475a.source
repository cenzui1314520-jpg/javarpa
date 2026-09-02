package com.rpa.server.controller;

import com.rpa.server.common.ApiException;
import com.rpa.server.common.R;
import com.rpa.server.entity.PublishRecord;
import com.rpa.server.entity.Script;
import com.rpa.server.entity.ScriptVersion;
import com.rpa.server.service.PublishService;
import com.rpa.server.service.ScriptService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/scripts")
public class ScriptController {
    private final ScriptService scriptService;
    private final PublishService publishService;

    public ScriptController(ScriptService scriptService, PublishService publishService) {
        this.scriptService = scriptService;
        this.publishService = publishService;
    }

    @GetMapping
    public R<List<Script>> list() {
        return R.ok(scriptService.list());
    }

    @PostMapping
    public R<Script> create(@RequestBody Map<String, String> body) {
        return R.ok(scriptService.create(body.get("name"), body.get("pkgName"), body.get("description")));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        scriptService.update(id, body.get("name"), body.get("description"));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        scriptService.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/versions")
    public R<List<ScriptVersion>> versions(@PathVariable long id) {
        return R.ok(scriptService.versions(id));
    }

    @PostMapping("/{id}/versions")
    public R<ScriptVersion> uploadVersion(@PathVariable long id,
                                          @RequestAttribute(value = "adminId", required = false) Long adminId,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam("versionCode") int versionCode,
                                          @RequestParam(value = "versionName", required = false) String versionName,
                                          @RequestParam(value = "changelog", required = false) String changelog) {
        if (file == null) throw new ApiException("请选择 zip 文件");
        return R.ok(scriptService.uploadVersion(id, file, versionCode, versionName, changelog,
                adminId == null ? "admin" : "admin#" + adminId));
    }

    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable long id,
                           @RequestAttribute(value = "adminId", required = false) Long adminId,
                           @RequestBody Map<String, Object> body) {
        int versionCode;
        try {
            versionCode = Integer.parseInt(String.valueOf(body.get("versionCode")));
        } catch (NumberFormatException e) {
            throw new ApiException("versionCode 必须为整数");
        }
        String targetType = body.get("targetType") == null ? null : String.valueOf(body.get("targetType"));
        String targetValue = body.get("targetValue") == null ? null : String.valueOf(body.get("targetValue"));
        publishService.publish(id, versionCode, targetType, targetValue,
                adminId == null ? "admin" : "admin#" + adminId);
        return R.ok();
    }

    @GetMapping("/{id}/publish-records")
    public R<List<PublishRecord>> publishRecords(@PathVariable long id) {
        return R.ok(publishService.records(id));
    }
}
