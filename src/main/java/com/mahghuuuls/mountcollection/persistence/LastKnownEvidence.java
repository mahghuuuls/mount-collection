package com.mahghuuuls.mountcollection.persistence;

public final class LastKnownEvidence {

    private final int dimensionId;
    private final double x;
    private final double y;
    private final double z;

    public LastKnownEvidence(int dimensionId, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("last-known coordinates must be finite");
        }
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getDimensionId() {
        return dimensionId;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LastKnownEvidence)) {
            return false;
        }
        LastKnownEvidence evidence = (LastKnownEvidence) other;
        return dimensionId == evidence.dimensionId
                && Double.compare(x, evidence.x) == 0
                && Double.compare(y, evidence.y) == 0
                && Double.compare(z, evidence.z) == 0;
    }

    @Override
    public int hashCode() {
        long result = dimensionId;
        result = 31L * result + Double.doubleToLongBits(x);
        result = 31L * result + Double.doubleToLongBits(y);
        result = 31L * result + Double.doubleToLongBits(z);
        return (int) (result ^ (result >>> 32));
    }
}
