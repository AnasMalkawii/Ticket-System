package com.mysecurity.auth.dto.refresh;

import com.mysecurity.auth.entity.RefreshToken;
import com.mysecurity.user.entity.User;

public record TokenValidationResult(
        User user,
        RefreshToken refreshToken,
        Long refreshTokenId
) {}
