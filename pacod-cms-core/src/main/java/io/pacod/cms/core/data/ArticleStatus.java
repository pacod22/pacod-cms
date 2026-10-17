package io.pacod.cms.core.data;

public enum ArticleStatus {
    NEW (0), DRAFT (1), PRIVATE (2), PUBLISHED (3);

    private final int value;

    private ArticleStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
