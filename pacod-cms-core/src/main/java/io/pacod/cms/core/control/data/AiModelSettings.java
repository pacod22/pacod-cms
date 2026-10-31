package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which model or template the installation's AI features call: a default and, per feature, an
 * optional override (feature name in lower case → a model id or {@code @template}), chosen on the
 * studio's platform screen or through its internal API from the gateway's own list. While this
 * document exists its entries replace {@code pacod.ai.model} and {@code pacod.ai.models.*}; an
 * entry it lacks leaves the property in force. One document, id {@value #ID}, in the control plane.
 *
 * <p>Apart from {@link AiGatewaySettings}, so going back to the environment's gateway keeps the
 * choice of models.
 */
@Data
@Document(collection = "aiModelSettings")
public class AiModelSettings {

    public static final String ID = "models";

    @Id
    private String id = ID;

    /** The default for every feature without an override; null leaves {@code pacod.ai.model}. */
    private String defaultModel;

    private Map<String, String> features = new LinkedHashMap<>();

    private Instant updatedOn;

    private String updatedBy;
}
