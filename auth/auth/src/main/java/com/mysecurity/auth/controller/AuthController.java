package com.mysecurity.auth.controller;

import com.mysecurity.auth.dto.login.LoginRequest;
import com.mysecurity.auth.dto.login.LoginResponse;
import com.mysecurity.auth.dto.logout.LogoutResponse;
import com.mysecurity.auth.dto.refresh.RefreshResponse;
import com.mysecurity.auth.dto.register.RegisterRequest;
import com.mysecurity.auth.dto.register.RegisterResponse;
import com.mysecurity.auth.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/api/auth")
public class AuthController {
    private final AuthService authService;

    @PostMapping(path = "/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest registerRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registerRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest loginRequest, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        String deviceIp = httpServletRequest.getRemoteAddr();
        return ResponseEntity.ok(authService.login(loginRequest, deviceIp, httpServletResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        return ResponseEntity.ok(authService.logout(httpServletRequest, httpServletResponse));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        return ResponseEntity.ok(authService.refresh(httpServletRequest, httpServletResponse));
    }


}
