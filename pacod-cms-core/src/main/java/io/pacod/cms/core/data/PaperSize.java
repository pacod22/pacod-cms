package io.pacod.cms.core.data;

/** The paper a website's PDFs are rendered on, in Gotenberg's unit, inches. */
public enum PaperSize {

    A4(8.27, 11.7),
    LETTER(8.5, 11),
    A5(5.83, 8.27);

    private final double widthInches;
    private final double heightInches;

    PaperSize(double widthInches, double heightInches) {
        this.widthInches = widthInches;
        this.heightInches = heightInches;
    }

    public double widthInches() {
        return widthInches;
    }

    public double heightInches() {
        return heightInches;
    }
}
