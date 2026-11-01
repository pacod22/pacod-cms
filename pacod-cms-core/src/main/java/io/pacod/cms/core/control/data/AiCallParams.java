package io.pacod.cms.core.control.data;

/**
 * What one AI feature sends with every call beside its model ({@link AiModelSettings#getParams()}):
 * each null is "not sent", leaving it to the gateway's template or the model.
 *
 * @param maxTokens       a minimum output budget: the feature's own estimate is raised to it,
 *                        never lowered; the gateway still caps it at the template's, the grant's
 *                        and the model's ceiling
 * @param temperature     0 to 2; a template that locks or omits it wins
 * @param reasoningEffort a level such as {@code low} or {@code none}, which the gateway fits to
 *                        the model's own levels
 */
public record AiCallParams(Integer maxTokens, Double temperature, String reasoningEffort) {

    public static final AiCallParams NONE = new AiCallParams(null, null, null);

    public boolean isEmpty() {
        return maxTokens == null && temperature == null && reasoningEffort == null;
    }
}
