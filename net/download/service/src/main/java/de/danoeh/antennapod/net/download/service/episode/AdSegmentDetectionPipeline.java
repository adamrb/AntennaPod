package de.danoeh.antennapod.net.download.service.episode;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.storage.database.DBWriter;
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

    public static void detectAsync(FeedMedia media) {
        analysisExecutor.submit(() -> {
            try {
                List<AdSegment> segments = detect(media);
                DBWriter.setAdSegments(media.getItemId(), segments);
            } catch (Exception e) {
                Log.e(TAG, "Ad segment detection failed", e);
            }
        });
    }

    public static List<AdSegment> detect(FeedMedia media) {
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

        List<AdSegment> fromAudio = AdSegmentDetector.detect(media.getLocalFileUrl());
        Log.d(TAG, "Using " + fromAudio.size() + " ad segments from audio analysis");
        return fromAudio;
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
