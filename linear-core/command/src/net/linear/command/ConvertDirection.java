package net.linear.command;

import net.linear.RegionFileFormat;

/** Conversion direction for a {@link ConvertJob}. */
public enum ConvertDirection {
    MCA_TO_LINEAR,
    LINEAR_TO_MCA,
    MCA_TO_BLINEAR,
    BLINEAR_TO_MCA,
    LINEAR_TO_BLINEAR,
    BLINEAR_TO_LINEAR;

    /**
     * Plain-words direction: forward pairs copy toward the target format,
     * reverse pairs copy back to old saves.
     */
    public String plainDirection() {
        switch (this) {
            case MCA_TO_LINEAR:
                return "Old saves to new faster saves";
            case LINEAR_TO_MCA:
                return "New saves back to old";
            case MCA_TO_BLINEAR:
                return "Old saves to buffered faster saves";
            case BLINEAR_TO_MCA:
                return "Buffered saves back to old";
            case LINEAR_TO_BLINEAR:
                return "New faster saves to buffered faster saves";
            case BLINEAR_TO_LINEAR:
                return "Buffered faster saves to new faster saves";
            default:
                return name();
        }
    }

    /**
     * Target format of this direction. Jobs run union-per-target: every
     * convertible non-target file converts (e.g. toward LINEAR, both
     * {@code .mca} and {@code .b_linear} sources queue).
     */
    public RegionFileFormat targetFormat() {
        switch (this) {
            case LINEAR_TO_MCA:
            case BLINEAR_TO_MCA:
                return RegionFileFormat.ANVIL;
            case MCA_TO_BLINEAR:
            case LINEAR_TO_BLINEAR:
                return RegionFileFormat.B_LINEAR;
            case MCA_TO_LINEAR:
            case BLINEAR_TO_LINEAR:
            default:
                return RegionFileFormat.LINEAR;
        }
    }
}
