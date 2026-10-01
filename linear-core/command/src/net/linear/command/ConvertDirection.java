package net.linear.command;

/** Conversion direction for a {@link ConvertJob}. */
public enum ConvertDirection {
    MCA_TO_LINEAR,
    LINEAR_TO_MCA;

    /**
     * Plain-words direction: {@code MCA_TO_LINEAR} copies old saves to new
     * faster saves, {@code LINEAR_TO_MCA} copies new saves back to old.
     */
    public String plainDirection() {
        switch (this) {
            case MCA_TO_LINEAR:
                return "Old saves to new faster saves";
            case LINEAR_TO_MCA:
                return "New saves back to old";
            default:
                return name();
        }
    }
}
