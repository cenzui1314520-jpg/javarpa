package com.rpa.server.controller;

import com.rpa.server.common.R;
import com.rpa.server.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tokens")
public class TokenController {
    private final AuthService authService;

    public TokenController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public R<List<Object>> list() {
        return R.ok(authService.listTokens().stream().map(t -> (Object) t).toList());
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        return R.ok(authService.createToken(body.get("name")));
    }

    @PostMapping("/{id}/status")
    public R<Void> setStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        authService.setTokenStatus(id, Integer.parseInt(String.valueOf(body.get("status"))));
        return R.ok();
    }
}
