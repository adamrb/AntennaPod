package de.danoeh.antennapod.net.download.service.episode;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Integration tests running the shipped analyzer against real podcast audio.
 * Fixtures are generated locally with scripts/generateAdDetectionFixtures.sh
 * from real episodes (not committed to the repository). Tests are skipped
 * when the fixtures are absent.
 */
public class AdSegmentAnalyzerIntegrationTest {
    private static final File FIXTURE_DIR = new File("src/test/fixtures/addetection");

    /**
     * Baseline thresholds documenting current detector capability on real audio.
     * The acoustic detector reliably fires at ad onsets (transitions into the ad)
     * but does not always cover the full ad span, and conversational content with
     * embedded clips produces some false positives. Tightening these thresholds
     * requires improving the detector; loosening them means a regression.
     */
    private static final long ONSET_TOLERANCE_MS = 30_000;
    private static final long FALSE_POSITIVE_BUDGET_MS = 150_000;

    @Before
    public void requireFixtures() {
        assumeTrue("Fixtures not generated; run scripts/generateAdDetectionFixtures.sh",
                new File(FIXTURE_DIR, "single_ad.pcm").exists());
    }

    @Test
    public void testSingleRealAdOnsetDetected() throws IOException {
        List<AdSegment> segments = analyze("single_ad.pcm");
        assertDetectsOnset(segments, 600_000);
    }

    @Test
    public void testSecondAdOnsetDetectedAmongTwo() throws IOException {
        List<AdSegment> segments = analyze("two_ads.pcm");
        assertDetectsOnset(segments, 1_230_000);
    }

    @Test
    public void testFalsePositivesWithinBudgetOnPureContent() throws IOException {
        List<AdSegment> segments = analyze("no_ads.pcm");
        long flagged = 0;
        for (AdSegment segment : segments) {
            flagged += segment.getEnd() - segment.getStart();
        }
        assertTrue("Flagged " + flagged / 1000 + "s of pure content as ads: " + segments,
                flagged <= FALSE_POSITIVE_BUDGET_MS);
    }

    private void assertDetectsOnset(List<AdSegment> segments, long adStart) {
        for (AdSegment segment : segments) {
            if (Math.abs(segment.getStart() - adStart) <= ONSET_TOLERANCE_MS
                    || (segment.getStart() <= adStart && segment.getEnd() > adStart)) {
                return;
            }
        }
        throw new AssertionError("No segment near ad onset at " + adStart + "; got " + segments);
    }

    private List<AdSegment> analyze(String fixtureName) throws IOException {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        byte[] buffer = new byte[1 << 16];
        try (BufferedInputStream in = new BufferedInputStream(
                new FileInputStream(new File(FIXTURE_DIR, fixtureName)), 1 << 20)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                int usable = read - (read % 2);
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, usable).order(ByteOrder.LITTLE_ENDIAN);
                analyzer.addPcm(byteBuffer.asShortBuffer(), 1, AdSegmentAnalyzer.TARGET_SAMPLE_RATE);
            }
        }
        return analyzer.getSegments();
    }
}
