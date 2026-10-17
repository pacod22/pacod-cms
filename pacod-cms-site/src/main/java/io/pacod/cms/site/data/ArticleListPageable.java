package io.pacod.cms.site.data;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;

@Slf4j
@Data
@Builder
public class ArticleListPageable {

    private Integer id;

    private String name;

    private Page<ListItem> page;

    private final int MAX_PAGES = 6;

    private final String DEFAULT_SORT = "id,DESC";

    public int getMaxPages() {
        return MAX_PAGES;
    }

    public int getPageSize() {
        int pageSize = 0;

        if(page != null && page.getPageable() != null) {
            pageSize = page.getPageable().getPageSize();
        }

        return pageSize;
    }

    public String getSort() {
        return DEFAULT_SORT;
    }


    public int getPageNumber() {
        int pageNumber = 0;

        if (page != null) {
            pageNumber = page.getPageable().getPageNumber();
        }

        return pageNumber;
    }

    public int getStartPage() {
        int startPosition = 0;

        if(page != null) {
            if(page.getPageable().getPageNumber() + MAX_PAGES < page.getTotalPages()) {
                startPosition = page.getPageable().getPageNumber();
            } else {
                startPosition = (page.getTotalPages() - MAX_PAGES)>0?page.getTotalPages() - MAX_PAGES:0;
            }
        }
        log.debug("Start position: {}", startPosition);
        return startPosition;
    }

    public int getLastPage() {
        int lastPosition = 0;

        if(page != null) {
            if(page.getPageable().getPageNumber() + MAX_PAGES < page.getTotalPages()) {
                lastPosition = page.getPageable().getPageNumber() + MAX_PAGES - 1;
            } else {
                lastPosition = page.getTotalPages() - 1;
            }
        }

        log.debug("Last position: {}", lastPosition);
        return lastPosition;
    }

    public int getPreviousPage() {
        int previousPage = 0;

        if(page != null) {
            if(page.getPageable().getPageNumber() > 0 ) {
                previousPage = page.getPageable().getPageNumber() - 1;
            }
        }
        return previousPage;
    }

    public int getNextPage() {
        int nextPage = 0;

        if(page != null) {
            if(page.getPageable().getPageNumber() + 1 >= page.getTotalPages() ) {
                nextPage = page.getTotalPages() - 1;
            } else {
                nextPage = page.getPageable().getPageNumber() + 1;
            }
        }
        return nextPage;
    }

}
