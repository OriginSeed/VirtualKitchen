package com.processVisualisation.virtualKitchen.auth.service;

import com.mongodb.client.result.UpdateResult;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.model.Otp;
import com.processVisualisation.virtualKitchen.auth.model.OtpPurpose;
import com.processVisualisation.virtualKitchen.auth.repository.OtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OtpService: the resend window works (createdAt is set) and code use is atomic under concurrent requests. */
class OtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String EMAIL = "cook";

    private OtpRepository repository;
    private MongoTemplate mongoTemplate;
    private EmailService emailService;
    private PasswordEncoder encoder;
    private OtpService service;

    @BeforeEach
    void setUp() {
        repository = mock(OtpRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        emailService = mock(EmailService.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        service = new OtpService(repository, mongoTemplate, emailService, encoder, new OtpProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Otp openOtp() {
        Otp otp = new Otp();
        otp.setId("otp-1");
        otp.setEmail(EMAIL);
        otp.setPurpose(OtpPurpose.LOGIN);
        otp.setCodeHash("hash");
        otp.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5));
        return otp;
    }

    @Test
    void newCodeRecordsWhenItWasCreated() {
        service.generateAndSendOtp(EMAIL, OtpPurpose.LOGIN);

        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(repository).countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(OtpPurpose.LOGIN),
                eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(15)));
    }

    @Test
    void resendLimitRefusesANewCode() {
        when(repository.countByEmailAndPurposeAndCreatedAtAfter(any(), any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.generateAndSendOtp(EMAIL, OtpPurpose.LOGIN))
                .isInstanceOf(AuthException.class);
        verify(emailService, never()).sendOtpEmail(any(), any(), any());
    }

    @Test
    void attemptOverTheLimitIsRefusedWithoutCheckingTheCode() {
        when(repository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.LOGIN)).thenReturn(Optional.of(openOtp()));
        // The conditional increment matched nothing: the limit was already reached (perhaps by a concurrent guess).
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Otp.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, "123456", OtpPurpose.LOGIN))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    void aCodeCanBeUsedByOneRequestOnly() {
        when(repository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.LOGIN)).thenReturn(Optional.of(openOtp()));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Otp.class)))
                .thenReturn(openOtp());
        when(encoder.matches("123456", "hash")).thenReturn(true);
        // Another request with the same correct code marked it used first.
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Otp.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.verifyAndConsume(EMAIL, "123456", OtpPurpose.LOGIN))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void correctCodeIsConsumed() {
        when(repository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.LOGIN)).thenReturn(Optional.of(openOtp()));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Otp.class)))
                .thenReturn(openOtp());
        when(encoder.matches("123456", "hash")).thenReturn(true);
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Otp.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.verifyAndConsume(EMAIL, "123456", OtpPurpose.LOGIN);

        verify(mongoTemplate).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Otp.class));
    }
}
