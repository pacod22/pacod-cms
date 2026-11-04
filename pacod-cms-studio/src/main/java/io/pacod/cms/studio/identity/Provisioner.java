package io.pacod.cms.studio.identity;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * The one binding nobody else can make: the person who creates an organisation becomes its
 * {@code cms.org_admin}. Nobody holds a role at an organisation that did not exist a moment ago, so
 * the studio makes it as its own service account, which holds {@code cms.provisioner} at the
 * platform ({@code make id-provisioner} creates it).
 *
 * <p>The account's {@code pidt_} token ({@code pacod.id.provisioner-token}) is read by this class
 * alone, and this class has one method, whose role and scope are fixed: {@code org_admin}, at an
 * organisation. The manifest keeps the account from granting {@code platform_admin} or
 * {@code provisioner} even if that were changed.
 *
 * <p>Its second method, {@link #mintBrokerProvider}, is as fixed: a service account named
 * {@value #BROKER_ACCOUNT} in one organisation, {@code broker.provider} there, and its token. It
 * works when the account also holds {@code broker.provisioner} at the platform: pacod-id lets whoever
 * holds the acting product's grant permission at a scope ({@code cms.members.manage}, through
 * {@code cms.provisioner}) manage its service accounts, and broker's grant rules let
 * {@code broker.members.manage} give {@code broker.provider}. Without that role pacod-id refuses
 * and the organisation's websites are not announced.
 */
public class Provisioner {

    public static final String ROLE = "cms.org_admin";
    public static final String BROKER_ACCOUNT = "cms-broker";
    public static final String BROKER_ROLE = "broker.provider";

    /** A provider account's token, as {@link #mintBrokerProvider} made it. */
    public record Minted(String accountId, String token) {
    }

    private final String token;
    private final TokenExchange exchange;
    private final IdAdmin admin;

    public Provisioner(String token, TokenExchange exchange, IdAdmin admin) {
        this.token = token;
        this.exchange = exchange;
        this.admin = admin;
    }

    /**
     * Makes {@code userSub} org_admin of {@code organisationId}, which pacod-id must already know
     * (else {@code scope_unknown}). Already holding it is not an error.
     *
     * @return the feed position to wait for; -1 when there is nothing to wait for
     */
    public long makeOrgAdmin(String userSub, String organisationId) {
        try {
            return IdAdmin.seqOf(admin.post(exchange.forAdmin(token), "/bindings", Map.of(
                    "principal", userSub,
                    "role", ROLE,
                    "scope", "organisation:" + organisationId)));
        } catch (IdClient.IdException e) {
            if ("binding_exists".equals(e.code())) {
                return -1;
            }
            if (e.status() == 401) {
                exchange.forget(token);
            }
            throw e;
        }
    }

    /**
     * Makes (or finds) the organisation's {@value #BROKER_ACCOUNT} service account, binds it
     * {@value #BROKER_ROLE} at the organisation, mints a token, and revokes the account's other
     * tokens once the new one exists. The binding comes first: a token whose binding then failed
     * would be a live credential nobody recorded.
     */
    public Minted mintBrokerProvider(String organisationId) {
        try {
            String admin = exchange.forAdmin(token);
            String accountId = null;
            for (JsonNode a : this.admin.get(admin, "/service-accounts", Map.of("organisation", organisationId))
                    .path("service_accounts")) {
                if (BROKER_ACCOUNT.equals(a.path("name").asString("")) && !"disabled".equals(a.path("status").asString(""))) {
                    accountId = a.path("id").asString(null);
                    break;
                }
            }
            if (accountId == null) {
                accountId = this.admin.post(admin, "/service-accounts",
                        Map.of("name", BROKER_ACCOUNT, "organisation_id", organisationId)).path("id").asString(null);
            }
            if (accountId == null || !accountId.startsWith("svc_")) {
                throw new IdClient.IdException(502, "no_account", "pacod-id made no service account");
            }
            try {
                this.admin.post(admin, "/bindings", Map.of(
                        "principal", accountId, "role", BROKER_ROLE, "scope", "organisation:" + organisationId));
            } catch (IdClient.IdException e) {
                if (!"binding_exists".equals(e.code())) {
                    throw e;
                }
            }
            JsonNode made = this.admin.post(admin, "/service-accounts/" + accountId + "/tokens", Map.of());
            String secret = made.path("token").asString(null);
            String madeId = made.path("id").asString("");
            if (secret == null || secret.isBlank()) {
                throw new IdClient.IdException(502, "no_token", "pacod-id answered without a token");
            }
            for (JsonNode t : this.admin.get(admin, "/service-accounts/" + accountId + "/tokens", Map.of())
                    .path("tokens")) {
                String id = t.path("id").asString("");
                if (!id.equals(madeId) && id.startsWith("stk_") && !"revoked".equals(t.path("status").asString(""))) {
                    this.admin.delete(admin, "/service-tokens/" + id, Map.of());
                }
            }
            return new Minted(accountId, secret);
        } catch (IdClient.IdException e) {
            if (e.status() == 401) {
                exchange.forget(token);
            }
            throw e;
        }
    }
}
