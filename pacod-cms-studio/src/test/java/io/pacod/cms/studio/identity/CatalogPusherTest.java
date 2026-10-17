package io.pacod.cms.studio.identity;

import io.pacod.cms.core.identity.authz.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogPusherTest {

    @Test
    @DisplayName("the manifest goes as its JSON source under 'manifest', with an empty migrate")
    void pushesTheSource() throws Exception {
        Manifest manifest = Manifest.fromYaml(new ClassPathResource("authz/cms.yaml").getContentAsString(StandardCharsets.UTF_8));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://id-internal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://id-internal.test/internal/v1/catalog/cms"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.manifest.product").value("cms"))
                .andExpect(jsonPath("$.manifest.scope_types.website.parent").value("organisation"))
                .andExpect(jsonPath("$.manifest.roles.org_admin.protected.bypass").value("platform.manage"))
                .andExpect(jsonPath("$.manifest.grant.default").value("members.manage"))
                .andExpect(jsonPath("$.migrate").isEmpty())
                .andRespond(withSuccess("{\"product\":\"cms\",\"version\":1,\"changed\":true,\"seq\":3}",
                        MediaType.APPLICATION_JSON));

        CatalogPusher pusher = new CatalogPusher(new IdClient(builder.build(), JsonMapper.builder().build()), manifest);
        assertThat(pusher.pushOnce()).isTrue();
        assertThat(pusher.isPushed()).isTrue();
        server.verify();
    }
}
