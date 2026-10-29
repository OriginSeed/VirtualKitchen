package com.processVisualisation.virtualKitchen.ai.platform.evaluation.controller;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationFilter;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationQueryService;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Admin API for evaluating AI-generated step images: human quality scores, and per-model comparison of quality,
 * cost and time. Cost and time come from the analytics records; only the human scores are stored here.
 * <p>
 * Admin-only twice over, as {@code AdminAiAnalyticsController}: the {@code /api/v1/admin/**} URL rule requires the
 * ADMIN role, and every handler calls {@link CurrentUser#requireAdmin()}.
 */
@RestController
@RequestMapping("/api/v1/admin/ai-evaluation")
public class AdminAiEvaluationController {

    private final EvaluationQueryService queryService;
    private final EvaluationService evaluationService;

    public AdminAiEvaluationController(EvaluationQueryService queryService, EvaluationService evaluationService) {
        this.queryService = queryService;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/rubric")
    public ApiResponse<EvaluationRubric.View> rubric() {
        CurrentUser.requireAdmin();
        return ok(EvaluationRubric.view());
    }

    /** Image sets; {@code sort=unscored} lists unscored sets first, otherwise newest first. */
    @GetMapping("/sets")
    public ApiResponse<EvaluationQueryService.SetPage> sets(
            @ModelAttribute EvaluationFilter filter,
            @RequestParam(defaultValue = "unscored") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CurrentUser.requireAdmin();
        return ok(queryService.listSets(filter, sort, page, size));
    }

    @GetMapping("/sets/{setKey}")
    public ResponseEntity<ApiResponse<EvaluationQueryService.SetDetail>> set(@PathVariable String setKey) {
        CurrentUser.requireAdmin();
        return handle(() -> queryService.setDetail(setKey)
                .orElseThrow(() -> new EvaluationService.NotFoundException("No image set " + setKey)));
    }

    /** Creates ({@code revision} null) or edits one image's scores. */
    @PutMapping("/sets/{setKey}/images/{requestId}")
    public ResponseEntity<ApiResponse<EvaluationService.ImageSaveResult>> scoreImage(
            @PathVariable String setKey, @PathVariable String requestId,
            @RequestBody EvaluationService.ImageScoreInput input) {
        Long adminId = CurrentUser.requireAdmin();
        return handle(() -> evaluationService.saveImage(adminId, setKey, requestId, input));
    }

    /** Creates ({@code revision} null) or edits the set's verdict. */
    @PutMapping("/sets/{setKey}/verdict")
    public ResponseEntity<ApiResponse<SetEvaluation>> verdict(
            @PathVariable String setKey, @RequestBody EvaluationService.VerdictInput input) {
        Long adminId = CurrentUser.requireAdmin();
        return handle(() -> evaluationService.saveVerdict(adminId, setKey, input));
    }

    /** Per-model quality, cost and time over the image sets matching the filter. */
    @GetMapping("/compare")
    public ApiResponse<EvaluationQueryService.Comparison> compare(@ModelAttribute EvaluationFilter filter) {
        CurrentUser.requireAdmin();
        return ok(queryService.compare(filter));
    }

    private static <T> ResponseEntity<ApiResponse<T>> handle(Supplier<T> action) {
        try {
            return ResponseEntity.ok(ok(action.get()));
        } catch (EvaluationService.NotFoundException missing) {
            return failure(HttpStatus.NOT_FOUND, missing.getMessage());
        } catch (EvaluationService.ConflictException conflict) {
            return failure(HttpStatus.CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            return failure(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).timestamp(LocalDateTime.now()).build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.<T>builder()
                .success(false).message(message).timestamp(LocalDateTime.now()).build());
    }
}
