package io.pacod.cms.site.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ArticleList {

    private Integer id;

    private String name;

    private List<ListItem> articles;

    public String getLinkName() {
        String linkName = null;

        if (id != null) {
            linkName = id.toString();
        } else if (name != null) {
            linkName = name;
        } else {
            linkName = "";
        }

        return linkName;
    }

}
