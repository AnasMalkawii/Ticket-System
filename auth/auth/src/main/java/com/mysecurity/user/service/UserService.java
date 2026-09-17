package com.mysecurity.user.service;

import com.mysecurity.user.dto.UserResponse;
import com.mysecurity.user.exception.UserNotFoundException;
import com.mysecurity.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public UserResponse getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        return userRepository.findByUsername(username).map(user -> new UserResponse(
                user.getUsername(),
                user.getRole()
        )).orElseThrow(UserNotFoundException::new);

    }

}
