package io.pacod.cms.core.data;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Document(collection = "author")
public class Author {
    @Id
    private Integer id;

    private String firstname;
    private List<TextItem> firstnames;

    private String lastname;
    private List<TextItem> lastnames;

    private String title;
    private List<TextItem> titles;

    private String description;
    private List<TextItem> descriptions;

    public String fullname() {
        StringBuilder fullname = new StringBuilder(64);
        if (firstname != null) {
            fullname.append(firstname).append(' ');
        }

        if(lastname != null) {
            fullname.append(lastname);
        }

        return fullname.toString();
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getFirstname() {
        return firstname;
    }

    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }

    public List<TextItem> getFirstnames() {
        return firstnames;
    }

    public void setFirstnames(List<TextItem> firstnames) {
        this.firstnames = firstnames;
    }

    public String getLastname() {
        return lastname;
    }

    public void setLastname(String lastname) {
        this.lastname = lastname;
    }

    public List<TextItem> getLastnames() {
        return lastnames;
    }

    public void setLastnames(List<TextItem> lastnames) {
        this.lastnames = lastnames;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<TextItem> getTitles() {
        return titles;
    }

    public void setTitles(List<TextItem> titles) {
        this.titles = titles;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<TextItem> getDescriptions() {
        return descriptions;
    }

    public void setDescriptions(List<TextItem> descriptions) {
        this.descriptions = descriptions;
    }

    public Author() {
        this.id = 0;
        this.firstname = null;
        this.firstnames = new ArrayList<>();
        this.lastname = null;
        this.lastnames = new ArrayList<>();
        this.title = null;
        this.titles = new ArrayList<>();
        this.description = null;
        this.descriptions = null;
    }

    public Author(Integer id) {
        this.id = id;
        this.firstname = null;
        this.firstnames = new ArrayList<>();
        this.lastname = null;
        this.lastnames = new ArrayList<>();
        this.title = null;
        this.titles = new ArrayList<>();
        this.description = null;
        this.descriptions = null;
    }

    public Author(Integer id, String firstname, List<TextItem> firstnames, String lastname, List<TextItem> lastnames, String title, List<TextItem> titles, String description, List<TextItem> descriptions) {
        this.id = id;
        this.firstname = firstname;
        this.firstnames = firstnames;
        this.lastname = lastname;
        this.lastnames = lastnames;
        this.title = title;
        this.titles = titles;
        this.description = description;
        this.descriptions = descriptions;
    }
}
