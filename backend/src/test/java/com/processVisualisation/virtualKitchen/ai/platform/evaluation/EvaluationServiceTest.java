package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService.ConflictException;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService.ImageSaveResult;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService.ImageScoreInput;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService.NotFoundException;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService.VerdictInput;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.ImageSet;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.InMemoryEvaluationStore;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.T0;
import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.image;
import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

    private static final String SET = "7::wf1";

    private InMemoryEvaluationStore store;
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        ImageSet set = EvaluationSets.group(List.of(
                image("s1", "7", "wf1", "s1", "gemini-image", 0.04, 0, 1000),
                image("s2", "7", "wf1", "s2", "gemini-image", 0.04, 1, 1000),
                row("s3", "7", "wf1", "s3", "gemini-image", AnalyticsStatus.FAILED, 0.04, 2, 1000))).get(0);
        EvaluationQueryService queryService = mock(EvaluationQueryService.class);
        when(queryService.loadSet(anyString())).thenReturn(Optional.empty());
        when(queryService.loadSet(SET)).thenReturn(Optional.of(set));
        store = new InMemoryEvaluationStore();
        service = new EvaluationService(store, queryService, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void savingAnImageScore_storesIt_andRecomputesTheSet() {
        ImageSaveResult result = service.saveImage(1L, SET, "s1",
                new ImageScoreInput(5, 5, 5, 5, List.of(), "  great  ", null));

        assertThat(result.image().getImageScore()).isEqualTo(100.0);
        assertThat(result.image().getNote()).isEqualTo("great");
        assertThat(result.image().getModelKey()).isEqualTo("gemini-image");
        assertThat(result.image().getRubricVersion()).isEqualTo(EvaluationRubric.VERSION);
        assertThat(result.image().getRevision()).isEqualTo(1);
        SetEvaluation set = result.set();
        assertThat(set.getScoredImages()).isEqualTo(1);
        assertThat(set.getTotalImages()).isEqualTo(2); // the failed request is not an image
        assertThat(set.getRecipeScore()).isEqualTo(100.0);
        assertThat(set.getVerdict()).isNull();
    }

    @Test
    void editingKeepsTheEarlierValues_andAStaleRevisionIsAConflict() {
        service.saveImage(1L, SET, "s1", new ImageScoreInput(5, 5, 5, 5, List.of(), null, null));
        ImageSaveResult edited = service.saveImage(2L, SET, "s1",
                new ImageScoreInput(2, 2, 2, 2, List.of("WRONG_COOKWARE"), null, 1L));

        assertThat(edited.image().getRevision()).isEqualTo(2);
        assertThat(edited.image().getEdits()).hasSize(1);
        assertThat(edited.image().getEdits().get(0).getConsistency()).isEqualTo(5);
        assertThat(edited.image().getUpdatedBy()).isEqualTo(2L);
        assertThat(edited.set().isHardFail()).isTrue(); // step correctness 2

        assertThatThrownBy(() -> service.saveImage(1L, SET, "s1", new ImageScoreInput(4, 4, 4, 4, List.of(), null, 1L)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.saveImage(1L, SET, "s1", new ImageScoreInput(4, 4, 4, 4, List.of(), null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void verdict_isSavedWithTheImageScores_andAcceptedOnlyWithoutAHardFail() {
        service.saveImage(1L, SET, "s1", new ImageScoreInput(4, 4, 4, 4, List.of(), null, null));
        long revision = store.findSet(SET).orElseThrow().getRevision();

        SetEvaluation saved = service.saveVerdict(1L, SET,
                new VerdictInput("ACCEPTED", List.of("OTHER"), "fine", revision));

        assertThat(saved.getVerdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(saved.isAccepted()).isTrue();
        // ratings of 4 (75) weigh 85, no defects (100) weighs 15
        assertThat(saved.getRecipeScore()).isEqualTo(78.75);

        assertThatThrownBy(() -> service.saveVerdict(1L, SET, new VerdictInput("REJECTED", List.of(), null, revision)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidInput_andUnknownSetsOrImages_areRejected() {
        assertThatThrownBy(() -> service.saveImage(1L, SET, "s1", new ImageScoreInput(6, null, null, null, List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveImage(1L, SET, "s1", new ImageScoreInput(3, null, null, null, List.of("NOPE"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveVerdict(1L, SET, new VerdictInput("MAYBE", List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveImage(1L, SET, "s3", new ImageScoreInput(3, null, null, null, List.of(), null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.saveImage(1L, "8::other", "s1", new ImageScoreInput(3, null, null, null, List.of(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }
}
