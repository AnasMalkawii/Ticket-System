package com.mysecurity.auth.service.refreshtoken;


import com.mysecurity.auth.dto.refresh.TokenValidationResult;
import com.mysecurity.auth.entity.RefreshToken;
import com.mysecurity.user.entity.User;
import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.exception.ExpiredTokenException;
import com.mysecurity.auth.exception.InvalidToken;
import com.mysecurity.auth.exception.MissingTokenException;
import com.mysecurity.auth.exception.RevokedToken;
import com.mysecurity.user.exception.UserNotFoundException;
import com.mysecurity.auth.jwt.core.JwtService;
import com.mysecurity.auth.repository.RefreshTokenRepository;
import com.mysecurity.user.repository.UserRepository;
import com.mysecurity.auth.util.TokenHash;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefreshTokenValidator {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public TokenValidationResult validate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new MissingTokenException();
        }

        Claims claims;
        Long id;
        try {
            claims = jwtService.extractAllClaims(refreshToken, TokenType.REFRESH);
            id = Long.parseLong(claims.getId());
        } catch (ExpiredJwtException e) {
            throw new ExpiredTokenException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidToken();
        }

        String username = claims.getSubject();
        User user = userRepository.findByUsername(username).orElseThrow(UserNotFoundException::new);

        if (!jwtService.isTokenValid(refreshToken, user, TokenType.REFRESH)) {
            throw new InvalidToken();
        }

        RefreshToken storedRefreshToken = refreshTokenRepository.findById(id).orElseThrow(InvalidToken::new);

        // Confirm this is the exact token we issued for this id and owner before
        // trusting its state - otherwise an attacker could probe/abuse other ids.
        String hashedRawToken = TokenHash.hash(refreshToken);
        if (!hashedRawToken.equals(storedRefreshToken.getTokenHash())
                || !storedRefreshToken.getUser().getId().equals(user.getId())) {
            throw new InvalidToken();
        }

        // Reuse detection: a genuine, matching token that is already revoked means it
        // was replayed (e.g. stolen after rotation). Revoke the whole family so neither
        // the attacker nor the victim can keep using it - both must re-authenticate.
        if (storedRefreshToken.isRevoked()) {
            // Commit the family revocation in a separate transaction so it survives the
            // rollback triggered by the RevokedToken we throw to reject this request.
            refreshTokenService.revokeAllForUser(user.getId());
            throw new RevokedToken();
        }

        return new TokenValidationResult(user, storedRefreshToken, id);
    }
}
