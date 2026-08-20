package de.danoeh.antennapod.model.feed;

import java.util.List;
import java.util.Objects;

public class AdSegment {
    private long id;
    private long start;
    private long end;
    private float confidence;

    public AdSegment() {
    }

    public AdSegment(long start, long end, float confidence) {
        this.start = start;
        this.end = end;
        this.confidence = confidence;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public static AdSegment getSegmentAtPosition(List<AdSegment> segments, long position) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        for (AdSegment segment : segments) {
            if (position >= segment.getStart() && position < segment.getEnd()) {
                return segment;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "AdSegment [start=" + start + ", end=" + end + ", confidence=" + confidence + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AdSegment segment = (AdSegment) o;
        return id == segment.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
