package de.danoeh.antennapod.net.download.service.episode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Chapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdChapterDetectorTest {

    @Test
    public void testDetectsSponsorChapter() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter(0, "Introduction", null, null),
                new Chapter(89_000, "Sponsors, Comments, and Reflections", null, null),
                new Chapter(504_000, "Why Dagestan produces so many great fighters", null, null));
        List<AdSegment> segments = AdChapterDetector.detect(chapters, 3_600_000);

        assertEquals(1, segments.size());
        assertEquals(89_000, segments.get(0).getStart());
        assertEquals(504_000, segments.get(0).getEnd());
        assertEquals(1.0f, segments.get(0).getConfidence(), 0.001f);
    }

    @Test
    public void testLastChapterUsesDuration() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter(0, "Show", null, null),
                new Chapter(3_000_000, "Ad break", null, null));
        List<AdSegment> segments = AdChapterDetector.detect(chapters, 3_600_000);

        assertEquals(1, segments.size());
        assertEquals(3_600_000, segments.get(0).getEnd());
    }

    @Test
    public void testIgnoresNormalChapters() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter(0, "Introduction", null, null),
                new Chapter(100_000, "The history of advertising in America", null, null),
                new Chapter(500_000, "Interview", null, null));
        assertTrue(AdChapterDetector.detect(chapters, 3_600_000).isEmpty());
    }

    @Test
    public void testNullAndEmptyChapters() {
        assertTrue(AdChapterDetector.detect(null, 1_000_000).isEmpty());
        assertTrue(AdChapterDetector.detect(new ArrayList<>(), 1_000_000).isEmpty());
    }

    @Test
    public void testTitleMatching() {
        assertTrue(AdChapterDetector.isAdTitle("Sponsors"));
        assertTrue(AdChapterDetector.isAdTitle("SPONSOR: NordVPN"));
        assertTrue(AdChapterDetector.isAdTitle("Ad break"));
        assertTrue(AdChapterDetector.isAdTitle("Commercial"));
        assertFalse(AdChapterDetector.isAdTitle("The history of advertising in America"));
        assertFalse(AdChapterDetector.isAdTitle("Adam Smith interview"));
        assertFalse(AdChapterDetector.isAdTitle(null));
    }
}
