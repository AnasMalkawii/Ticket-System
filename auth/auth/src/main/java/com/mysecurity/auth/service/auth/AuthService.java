package com.mysecurity.auth.service.auth;

import com.mysecurity.auth.dto.login.LoginRequest;
import com.mysecurity.auth.dto.login.LoginResponse;
import com.mysecurity.auth.dto.logout.LogoutResponse;
import com.mysecurity.auth.dto.refresh.RefreshResponse;
import com.mysecurity.auth.dto.refresh.TokenValidationResult;
import com.mysecurity.auth.dto.register.RegisterRequest;
import com.mysecurity.auth.dto.register.RegisterResponse;
import com.mysecurity.auth.entity.RefreshToken;
import com.mysecurity.user.entity.User;
import com.mysecurity.auth.enums.Role;
import com.mysecurity.user.exception.DuplicateUsernameException;
import com.mysecurity.auth.exception.InvalidCredentials;
import com.mysecurity.auth.exception.MissingTokenException;
import com.mysecurity.auth.repository.RefreshTokenRepository;
import com.mysecurity.user.repository.UserRepository;
import com.mysecurity.auth.service.refreshtoken.RefreshTokenService;
import com.mysecurity.auth.service.refreshtoken.RefreshTokenValidator;
import com.mysecurity.auth.config.CookieProperties;
import com.mysecurity.auth.util.CookieUtil;
import com.mysecurity.auth.util.TokenExtractor;
import com.mysecurity.auth.util.TokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenValidator refreshTokenValidator;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final CookieProperties cookieProperties;



    @Transactional
    public RegisterResponse register(RegisterRequest registerRequest) {
        if(userRepository.existsByUsername(registerRequest.username())){
            throw new DuplicateUsernameException();
        }
        User user = User.builder()
                .username(registerRequest.username())
                .password(passwordEncoder.encode(registerRequest.password()))
                .role(Role.USER)
                .build();
        userRepository.save(user);
        return new RegisterResponse("User registered successfully");
    }

    @Transactional
    public LoginResponse login(LoginRequest loginRequest, String deviceIp, HttpServletResponse httpServletResponse) {
        User user = userRepository.findByUsername(loginRequest.username()).orElseThrow(InvalidCredentials::new);
        if(!passwordEncoder.matches(loginRequest.password(), user.getPassword())){
            throw new InvalidCredentials();
        }

        String accessToken = tokenIssuer.issuerTokens(user, deviceIp, httpServletResponse);

        return new LoginResponse("Logged In successfully", accessToken);
    }

    @Transactional
    public LogoutResponse logout(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        String oldRefreshToken = TokenExtractor.extractRefreshToken(httpServletRequest);
        TokenValidationResult result = refreshTokenValidator.validate(oldRefreshToken);

        // validate() guarantees the token exists and is not already revoked.
        RefreshToken refreshToken = result.refreshToken();
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        SecurityContextHolder.clearContext();
        CookieUtil.removeRefreshTokenFromCookie(httpServletResponse, cookieProperties);

        return new LogoutResponse("Logout successfully");

    }

    @Transactional
    public RefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String oldToken = TokenExtractor.extractRefreshToken(request);
        if (oldToken == null) throw new MissingTokenException();

        TokenValidationResult result = refreshTokenValidator.validate(oldToken);
        User user = result.user();

        refreshTokenService.revokeRefreshToken(result.refreshTokenId());

        String newAccessToken = tokenIssuer.issuerTokens(user, request.getRemoteAddr(), response);

        return new RefreshResponse(newAccessToken);
    }

}
