package io.pacod.cms.core.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tag")
public class Tag {
    @Id
    private String name;
    private List<TextItem> translations;

    public boolean isTranslationEmpty() {
        return translations == null || translations.isEmpty();
    }

    public boolean isTranslationExisting(String lang) {

        return translations != null && !translations.isEmpty() && translations.stream().anyMatch(t -> t.getLang().equals(lang));
    }

    public String translate(String lang) {
        String transalatedTag;
        if (lang != null && LanguageChain.original().equalsIgnoreCase(lang)) {
            transalatedTag = name;
        } else if (lang != null && isTranslationExisting(lang)) {
            transalatedTag = translations.stream().filter(t -> t.getLang().equals(lang)).findFirst().get().getText();
        } else {
            if(isTranslationExisting("en")) {
                transalatedTag = translations.stream().filter(t -> t.getLang().equals("en")).findFirst().get().getText();
            } else {
                transalatedTag = name;
            }
        }

        return transalatedTag;
    }
}
