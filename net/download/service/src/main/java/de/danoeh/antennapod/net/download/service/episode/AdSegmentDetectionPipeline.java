package de.danoeh.antennapod.net.download.service.episode;

import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.event.AdAnalysisProgressEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

public final class AdSegmentDetectionPipeline {
    private static final String TAG = "AdSegmentPipeline";
    private static final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("AdSegmentAnalysis");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private AdSegmentDetectionPipeline() {
    }

    public static void detectAsync(Context context, FeedMedia media) {
        analysisExecutor.submit(() -> {
            try {
                List<AdSegment> segments = detect(context, media);
                DBWriter.setAdSegments(media.getItemId(), segments);
                if (media.getItem() != null) {
                    EventBus.getDefault().post(
                            new FeedItemEvent(Collections.singletonList(media.getItem()), false));
                }
            } catch (Exception e) {
                Log.e(TAG, "Ad segment detection failed", e);
            } finally {
                EventBus.getDefault().post(AdAnalysisProgressEvent.done(media.getItemId()));
                NotificationManagerCompat.from(context).cancel(R.id.notification_ad_analysis);
            }
        });
    }

    public static List<AdSegment> detect(Context context, FeedMedia media) {
        long duration = media.getDuration();

        List<AdSegment> fromChapters = AdChapterDetector.detect(media.getChapters(), duration);
        if (!fromChapters.isEmpty()) {
            Log.d(TAG, "Using " + fromChapters.size() + " ad segments from chapter titles");
            return fromChapters;
        }

        List<AdSegment> fromTranscript = detectFromTranscript(media, duration);
        if (!fromTranscript.isEmpty()) {
            Log.d(TAG, "Using " + fromTranscript.size() + " ad segments from transcript");
            return fromTranscript;
        }

        List<AdSegment> fromAudio = AdSegmentDetector.detect(media.getLocalFileUrl(),
                percent -> reportProgress(context, media, percent));
        Log.d(TAG, "Using " + fromAudio.size() + " ad segments from audio analysis");
        return fromAudio;
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
