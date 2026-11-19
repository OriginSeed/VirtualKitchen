package com.processVisualisation.virtualKitchen.ai.recipe;

import com.processVisualisation.virtualKitchen.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiJobAdmissionTest {

    private static boolean userQuery(Query query) {
        return query != null && query.getQueryObject().containsKey("userId");
    }

    @Test
    void allowsAJobUnderBothLimits() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.count(any(Query.class), any(Class.class))).thenReturn(1L);

        assertThatCode(() -> new AiJobAdmission(mongo, 10, 60).checkCanStart(7L)).doesNotThrowAnyException();
    }

    @Test
    void refusesAUserWithTooManyActiveJobs() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.count(argThat(AiJobAdmissionTest::userQuery), any(Class.class))).thenReturn(3L); // 4 kinds x 3 = 12
        when(mongo.count(argThat(query -> query != null && !userQuery(query)), any(Class.class))).thenReturn(0L);

        assertThatThrownBy(() -> new AiJobAdmission(mongo, 10, 60).checkCanStart(7L))
                .isInstanceOf(AiJobAdmission.AiJobLimitException.class)
                .hasMessageContaining("several AI tasks")
                .satisfies(e -> {
                    AiJobAdmission.AiJobLimitException limit = (AiJobAdmission.AiJobLimitException) e;
                    assertThat(limit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(limit.getCode()).isEqualTo(ErrorCode.AI_BUSY);
                });
    }

    @Test
    void refusesWhenTooManyJobsAreWaitingSystemWide() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.count(argThat(AiJobAdmissionTest::userQuery), any(Class.class))).thenReturn(0L);
        when(mongo.count(argThat(query -> query != null && !userQuery(query)), any(Class.class))).thenReturn(20L); // 80 queued

        assertThatThrownBy(() -> new AiJobAdmission(mongo, 10, 60).checkCanStart(7L))
                .isInstanceOf(AiJobAdmission.AiJobLimitException.class)
                .hasMessageContaining("busy");
    }
}
