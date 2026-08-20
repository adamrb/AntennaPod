package de.danoeh.antennapod.event;

public class AdAnalysisProgressEvent {
    public static final int PROGRESS_DONE = -1;

    public final long feedItemId;
    public final int percent;

    private AdAnalysisProgressEvent(long feedItemId, int percent) {
        this.feedItemId = feedItemId;
        this.percent = percent;
    }

    public static AdAnalysisProgressEvent progress(long feedItemId, int percent) {
        return new AdAnalysisProgressEvent(feedItemId, percent);
    }

    public static AdAnalysisProgressEvent done(long feedItemId) {
        return new AdAnalysisProgressEvent(feedItemId, PROGRESS_DONE);
    }

    public boolean isDone() {
        return percent == PROGRESS_DONE;
    }
}
