package io.pacod.cms.studio.identity;

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
 */
public class Provisioner {

    public static final String ROLE = "cms.org_admin";

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
}
