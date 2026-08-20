package de.danoeh.antennapod.net.download.service.episode;

import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.event.AdAnalysisProgressEvent;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

public final class AdSegmentDetectionPipeline {
    private static final String TAG = "AdSegmentPipeline";

    private AdSegmentDetectionPipeline() {
    }

    public static void detectAsync(Context context, FeedMedia media) {
        AdDetectionWorker.enqueue(context, media);
    }

    public static List<AdSegment> detect(Context context, FeedMedia media) {
        return detect(context, media, null);
    }

    public static List<AdSegment> detect(Context context, FeedMedia media, StringBuilder trace) {
        long duration = media.getDuration();
        trace(trace, "Episode: " + media.getEpisodeTitle());
        trace(trace, "Duration: " + duration / 1000 + "s, local file: " + media.getLocalFileUrl());

        List<Chapter> chapters = media.getChapters();
        trace(trace, "Chapters: " + (chapters == null ? "none" : chapters.size()));
        List<AdSegment> fromChapters = AdChapterDetector.detect(chapters, duration);
        if (!fromChapters.isEmpty()) {
            Log.d(TAG, "Using " + fromChapters.size() + " ad segments from chapter titles");
            trace(trace, "Signal used: CHAPTER TITLES -> " + fromChapters.size() + " segment(s)");
            traceSegments(trace, fromChapters);
            return fromChapters;
        }

        trace(trace, "Transcript URL: " + (media.getItem() == null
                ? "no item" : media.getItem().getTranscriptUrl()));
        List<AdSegment> fromTranscript = detectFromTranscript(media, duration);
        if (!fromTranscript.isEmpty()) {
            Log.d(TAG, "Using " + fromTranscript.size() + " ad segments from transcript");
            trace(trace, "Signal used: TRANSCRIPT -> " + fromTranscript.size() + " segment(s)");
            traceSegments(trace, fromTranscript);
            return fromTranscript;
        }

        trace(trace, "Falling back to audio analysis");
        List<AdSegment> fromAudio = AdSegmentDetector.detect(media.getLocalFileUrl(),
                percent -> reportProgress(context, media, percent), trace);
        Log.d(TAG, "Using " + fromAudio.size() + " ad segments from audio analysis");
        trace(trace, "Signal used: AUDIO ANALYSIS -> " + fromAudio.size() + " segment(s)");
        traceSegments(trace, fromAudio);
        return fromAudio;
    }

    private static void trace(StringBuilder trace, String message) {
        if (trace != null) {
            trace.append(message).append('\n');
        }
    }

    private static void traceSegments(StringBuilder trace, List<AdSegment> segments) {
        if (trace == null) {
            return;
        }
        for (AdSegment segment : segments) {
            trace.append(String.format(Locale.US, "  %s - %s (conf %.2f)%n",
                    formatTime(segment.getStart()), formatTime(segment.getEnd()), segment.getConfidence()));
        }
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private static void reportProgress(Context context, FeedMedia media, int percent) {
        EventBus.getDefault().post(AdAnalysisProgressEvent.progress(media.getItemId(), percent));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                NotificationUtils.CHANNEL_ID_DOWNLOADING)
                .setContentTitle(context.getString(R.string.ad_analysis_notification_title))
                .setContentText(media.getEpisodeTitle())
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false);
        try {
            NotificationManagerCompat.from(context).notify(R.id.notification_ad_analysis, builder.build());
        } catch (SecurityException e) {
            Log.d(TAG, "No notification permission");
        }
    }

    private static List<AdSegment> detectFromTranscript(FeedMedia media, long duration) {
        try {
            if (media.getItem() == null || media.getItem().getTranscriptUrl() == null) {
                return new ArrayList<>();
            }
            Transcript transcript = TranscriptUtils.loadTranscript(media, false);
            return AdTranscriptDetector.detect(transcript, duration);
        } catch (Exception e) {
            Log.e(TAG, "Transcript ad detection failed", e);
            return new ArrayList<>();
        }
    }
}
