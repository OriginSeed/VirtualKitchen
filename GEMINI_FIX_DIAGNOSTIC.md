# Gemini Recipe Flow Generation - Diagnostic Guide

## Your Original Issue
Gemini returned valid JSON structure but with type mismatches:
- `quantity=2` (number) instead of `quantity="2 cups"` (string)
- `durationValue=20` (number) instead of `durationValue="20"` (string)

This caused validation failures in the UI's `normalizeGeneratedFlowData()` function.

## How the Fix Works

### Phase 1: Generation
```
┌─────────────────────────────────────────────────┐
│         Gemini Gets Improved Prompt             │
│                                                 │
│  "CRITICAL TYPE RULES:                          │
│   - ALL structured fields MUST be strings       │
│   - quantity=\"2 cups\" NOT quantity=2          │
│   - durationValue=\"20\" NOT durationValue=20"  │
└─────────────────────────────────────────────────┘
              ↓
        More Likely to Generate
        Correct String Types
```

### Phase 2: Validation + Normalization
```
┌──────────────────────────────────────┐
│  JSON Received from Gemini           │
│  (might have type mismatches)        │
└──────────────────────────────────────┘
              ↓
┌──────────────────────────────────────┐
│  normalizeNodeDataTypes() Method     │
│                                      │
│  Converts all step/condition/        │
│  parallel fields to strings          │
└──────────────────────────────────────┘
              ↓
┌──────────────────────────────────────┐
│  Backend Validation (RecipeValidator)│
│                                      │
│  ✓ Structural validation             │
│  ✓ Type verification                 │
└──────────────────────────────────────┘
              ↓
┌──────────────────────────────────────┐
│  Frontend normalizeGeneratedFlowData()│
│                                      │
│  ✓ All fields are already strings    │
│  ✓ Validation passes                 │
└──────────────────────────────────────┘
```

## Validation Flow (2-Attempt System)

```
User Input: "2 cups rice, wash, add 4 cups water, cook 20 min"
              ↓
┌─────────────────────────────────┐
│  ATTEMPT 1: Initial Generation  │
│                                 │
│  1. Send to Gemini with prompt  │
│  2. Parse JSON response         │
│  3. Normalize field types       │
│  4. Validate structure          │
└─────────────────────────────────┘
              ↓
          ┌───┴────┐
          │         │
        Valid    Invalid
          │         │
          ✓         │
     Return      ┌───────────────────┐
     Result      │  ATTEMPT 2: Retry │
                 │                   │
                 │  1. Send errors   │
                 │     to Gemini     │
                 │  2. Repeat steps  │
                 │     1-4 above     │
                 └───────────────────┘
                         ↓
                     ┌───┴────┐
                     │         │
                   Valid    Invalid
                     │         │
                     ✓         ✗
                Return     Throw
                Result    Exception
```

## Expected Improvements

### Before This Fix
```
Gemini Response:
{
  "nodes": [{
    "data": {
      "step": {
        "quantity": 2,              ❌ NUMBER
        "durationValue": 20,        ❌ NUMBER
      }
    }
  }]
}
→ Validation Error in RecipeValidator
→ Second attempt required
```

### After This Fix
```
Gemini Response (Same as Before):
{
  "nodes": [{
    "data": {
      "step": {
        "quantity": 2,              ❌ NUMBER (input)
        "durationValue": 20,        ❌ NUMBER (input)
      }
    }
  }]
}
→ normalizeNodeDataTypes() converts:
  "quantity": "2",                  ✓ STRING (output)
  "durationValue": "20",            ✓ STRING (output)
→ Validation passes on first attempt!
```

## Configuration Details

### Prompt Changes (RecipeFlowPromptBuilder.java)
**Lines Added:**
- Line 47: "ALL FIELDS MUST BE STRINGS or empty strings"
- Line 49: "quantity: string with units (e.g. \"2 cups\", not just \"2\")"
- Line 52: "ALWAYS strings (e.g. durationValue=\"20\")"
- Lines 81-86: "CRITICAL TYPE RULES" section
- Line 101: Reminder at end about string formatting

### Backend Changes (RecipeGenerationService.java)
**New Methods:**
- `normalizeNodeDataTypes(List<Map>)` - Entry point for normalization
- `coerceObjectFieldsToStrings(Map)` - Converts individual fields

**Integration:**
- Called at line 92, right after JSON parsing
- Before validation, so RecipeValidator always receives correct types

## Testing the Fix

### Manual Test Case
```
Input Recipe: "Take 2 cups of rice. Wash twice. Cook for 20 minutes."

Expected Output (after fix):
✓ All numeric fields are strings
✓ quantity includes units: "2 cups"
✓ durationValue is string: "20"
✓ First validation attempt succeeds
```

### Debug Output
Look for these messages in backend logs:
```
[RECIPE-GEN] Start generate flow
[RECIPE-GEN] Success. (No "Validation failed" message = first attempt passed)
[AI] Success. provider=gemini model=gemini-2.5-flash latencyMs=XXX
```

## Edge Cases Handled

| Case | Behavior |
|------|----------|
| `null` value | Converted to empty string `""` |
| Number (e.g., `2`) | Converted to string `"2"` |
| Boolean (e.g., `true`) | Converted to string `"true"` |
| Empty string | Preserved as `""` |
| Already string | Left unchanged |
| Nested objects | Not touched (only top-level step/condition/parallel fields) |

## Troubleshooting

### Issue: Still getting validation errors?
1. Check backend logs for `[RECIPE-GEN]` messages
2. Verify `normalizeNodeDataTypes()` is being called (should be before validation)
3. Ensure fields in step/condition/parallel are being converted
4. Check if new required fields were added to schema

### Issue: Quantity showing without units?
1. This is expected - the prompt asks for "2 cups" but Gemini might return "2"
2. Frontend `normalizeStepNodeData()` will handle merging quantity with unit field
3. The prompt now explicitly requests format like "2 cups"

### Issue: Retry happening on first attempt?
1. Check the validation error messages
2. If "type" mismatch, normalization might not be catching all cases
3. Review the specific error in RecipeValidator logs

## Future Improvements

1. **Unit Parsing**: Add logic to split "2 cups" into quantity="2" and unit="cups"
2. **Field Validation**: Add regex patterns for valid flame options, units, etc.
3. **Response Format Enforcement**: Gemini can optionally return with `"response_format": "json_object"`
4. **Caching**: Cache prompt templates to reduce prompt engineering iterations
5. **Monitoring**: Log all generation attempts and retry patterns for analysis

