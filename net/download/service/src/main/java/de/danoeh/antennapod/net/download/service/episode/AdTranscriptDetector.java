package de.danoeh.antennapod.net.download.service.episode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

public final class AdTranscriptDetector {
    private static final String[] STRONG_PHRASES = {
            "this episode is brought to you by", "this show is brought to you by",
            "brought to you by", "sponsored by", "thanks to our sponsor", "thanks to our sponsors",
            "support for this show comes from", "support for this podcast comes from",
            "today's sponsor", "our sponsor", "use promo code", "use code", "promo code",
            "at checkout for", "percent off your", "% off your", "go to", "head over to"
    };
    private static final String[] WEAK_PHRASES = {
            "discount", "free trial", "free shipping", "terms apply", "cancel anytime",
            "for a limited time", "when you sign up", "dot com slash", ".com/",
            "check them out", "link in the description", "back to the show", "back to the episode"
    };
    private static final double DENSITY_THRESHOLD = 1.5;
    private static final long BIN_MS = 15000;
    private static final long MERGE_GAP_MS = 45000;
    private static final long MIN_SEGMENT_MS = 20000;
    private static final long MAX_SEGMENT_MS = 10 * 60 * 1000;

    private AdTranscriptDetector() {
    }

    public static List<AdSegment> detect(Transcript transcript, long durationMs) {
        List<AdSegment> result = new ArrayList<>();
        if (transcript == null || transcript.getSegmentCount() == 0 || durationMs <= 0) {
            return result;
        }
        int numBins = (int) (durationMs / BIN_MS) + 1;
        double[] density = new double[numBins];
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            TranscriptSegment segment = transcript.getSegmentAt(i);
            if (segment.getWords() == null) {
                continue;
            }
            String words = segment.getWords().toLowerCase(Locale.ROOT);
            double score = 0;
            for (String phrase : STRONG_PHRASES) {
                if (words.contains(phrase)) {
                    score += 1.5;
                }
            }
            for (String phrase : WEAK_PHRASES) {
                if (words.contains(phrase)) {
                    score += 0.5;
                }
            }
            if (score > 0) {
                int bin = (int) (segment.getStartTime() / BIN_MS);
                if (bin >= 0 && bin < numBins) {
                    density[bin] += score;
                }
            }
        }

        List<long[]> runs = new ArrayList<>();
        int runStart = -1;
        for (int b = 0; b <= numBins; b++) {
            boolean hot = b < numBins && density[b] >= DENSITY_THRESHOLD;
            if (hot) {
                if (runStart < 0) {
                    runStart = b;
                }
            } else if (runStart >= 0) {
                runs.add(new long[]{runStart * BIN_MS, b * BIN_MS});
                runStart = -1;
            }
        }

        List<long[]> merged = new ArrayList<>();
        for (long[] run : runs) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && run[0] - last[1] <= MERGE_GAP_MS) {
                last[1] = run[1];
            } else {
                merged.add(run);
            }
        }

        for (long[] run : merged) {
            long length = run[1] - run[0];
            if (length >= MIN_SEGMENT_MS && length <= MAX_SEGMENT_MS) {
                result.add(new AdSegment(run[0], Math.min(run[1], durationMs), 0.9f));
            }
        }
        return result;
    }
}
