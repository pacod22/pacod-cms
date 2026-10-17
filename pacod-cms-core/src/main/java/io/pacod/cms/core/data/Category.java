package io.pacod.cms.core.data;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Document(collection = "category")
public class Category {
    @Id
    private Integer id;

    private String name;
    private List<TextItem> names;
    private String lang;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<TextItem> getNames() {
        return names;
    }

    public void setNames(List<TextItem> names) {
        this.names = names;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public Category() {
        this.id = 0;
        this.name = "name";
        this.names = new ArrayList<>();
        this.lang = LanguageChain.original();
    }

    public Category(Integer id) {
        this.id = id;
        this.name = "name";
        this.names = new ArrayList<>();
        this.lang = LanguageChain.original();
    }

    public Category(Integer id, String name, List<TextItem> names, String lang) {
        this.id = id;
        this.name = name;
        this.names = names;
        this.lang = lang;
    }
}
