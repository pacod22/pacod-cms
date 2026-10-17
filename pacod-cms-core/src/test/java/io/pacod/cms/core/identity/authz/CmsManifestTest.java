package io.pacod.cms.core.identity.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pacod-cms's own manifest: it loads, and every role does what {@code authz/vectors/cms.yaml}
 * says it does.
 */
class CmsManifestTest {

    static Manifest cms() {
        return Manifest.fromYaml(AuthzEngineVectorsTest.read("authz/cms.yaml"));
    }

    @Test
    @DisplayName("the manifest loads, with the website under the organisation")
    void loads() {
        Manifest m = cms();
        assertThat(m.product()).isEqualTo("cms");
        assertThat(m.scopeTypes()).containsEntry("cms.website", "organisation");
        assertThat(m.roles()).extracting(Role::name).contains("cms.platform_admin", "cms.provisioner",
                "cms.org_admin", "cms.website_admin", "cms.editor", "cms.author", "cms.designer", "cms.analyst");
    }

    @Test
    @DisplayName("every product vector holds")
    void vectors() {
        VectorRunner.Result result = VectorRunner.run(AuthzEngineVectorsTest.read("authz/vectors/cms.yaml"),
                name -> cms());
        assertThat(result.failures()).isEmpty();
        assertThat(result.checks()).isGreaterThan(30);
    }

    @Test
    @DisplayName("no role is implicit: an implicit role would hold its grant permission everywhere")
    void noImplicitRoles() {
        assertThat(cms().roles()).noneMatch(Role::implicit);
    }

    @Test
    @DisplayName("making platform admins and provisioners takes platform.manage")
    void platformRolesNeedPlatformManage() {
        Manifest m = cms();
        assertThat(m.grantPermission("cms.platform_admin")).isEqualTo("cms.platform.manage");
        assertThat(m.grantPermission("cms.provisioner")).isEqualTo("cms.platform.manage");
        assertThat(m.grantPermission("cms.editor")).isEqualTo("cms.members.manage");
        assertThat(m.role("cms.provisioner").at()).isEqualTo(List.of("platform"));
    }
}
