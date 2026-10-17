package com.ticketsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.auth.dto.register.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 14, 15, 72})
    void acceptsPasswordsWithinTheSupportedLength(int length) {
        assertThat(VALIDATOR.validate(new RegisterRequest("musicfan", "a".repeat(length)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc12", "      "})
    void rejectsPasswordsThatAreTooShortOrBlank(String password) {
        assertThat(VALIDATOR.validate(new RegisterRequest("musicfan", password))).isNotEmpty();
    }
}
