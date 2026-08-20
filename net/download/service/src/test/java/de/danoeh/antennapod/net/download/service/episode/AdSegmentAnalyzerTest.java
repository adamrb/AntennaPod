package de.danoeh.antennapod.net.download.service.episode;

import org.junit.Test;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.Random;

import de.danoeh.antennapod.model.feed.AdSegment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdSegmentAnalyzerTest {
    private static final int SAMPLE_RATE = AdSegmentAnalyzer.TARGET_SAMPLE_RATE;
    private final Random random = new Random(42);

    @Test
    public void testUniformSpeechHasNoSegments() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addSpeech(analyzer, 600);
        assertEquals(0, analyzer.getSegments().size());
    }

    @Test
    public void testShortEpisodeReturnsNothing() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addMusicAd(analyzer, 60);
        assertEquals(0, analyzer.getSegments().size());
    }

    @Test
    public void testDetectsLoudMusicAdInSpeech() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addSpeech(analyzer, 300);
        addMusicAd(analyzer, 60);
        addSpeech(analyzer, 300);
        List<AdSegment> segments = analyzer.getSegments();

        assertEquals(1, segments.size());
        AdSegment segment = segments.get(0);
        assertTrue("segment " + segment + " should cover the ad",
                segment.getStart() <= 310_000 && segment.getEnd() >= 350_000);
        assertTrue("start " + segment.getStart(), segment.getStart() > 240_000);
        assertTrue("end " + segment.getEnd(), segment.getEnd() < 420_000);
        assertTrue(segment.getConfidence() > 0);
    }

    @Test
    public void testDetectsMultipleAdBreaks() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addSpeech(analyzer, 300);
        addMusicAd(analyzer, 45);
        addSpeech(analyzer, 400);
        addMusicAd(analyzer, 90);
        addSpeech(analyzer, 300);
        List<AdSegment> segments = analyzer.getSegments();

        assertEquals(2, segments.size());
        assertTrue(segments.get(0).getStart() <= 310_000 && segments.get(0).getEnd() >= 335_000);
        assertTrue(segments.get(1).getStart() <= 755_000 && segments.get(1).getEnd() >= 825_000);
    }

    @Test
    public void testIgnoresLongDivergentSection() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addSpeech(analyzer, 400);
        addMusicAd(analyzer, 400);
        addSpeech(analyzer, 400);
        for (AdSegment segment : analyzer.getSegments()) {
            assertTrue(segment.getEnd() - segment.getStart() <= 300_000);
        }
    }

    @Test
    public void testTimestampsAccurateAt44100Hz() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        analyzer.addPcm(ShortBuffer.wrap(synthSpeech(300, 44100, 1)), 1, 44100);
        analyzer.addPcm(ShortBuffer.wrap(synthMusic(60, 44100, 1)), 1, 44100);
        analyzer.addPcm(ShortBuffer.wrap(synthSpeech(300, 44100, 1)), 1, 44100);
        List<AdSegment> segments = analyzer.getSegments();

        assertEquals(1, segments.size());
        AdSegment segment = segments.get(0);
        assertTrue("segment " + segment + " should cover the ad",
                segment.getStart() <= 310_000 && segment.getEnd() >= 350_000);
        assertTrue("start " + segment.getStart(), segment.getStart() > 240_000);
        assertTrue("end " + segment.getEnd(), segment.getEnd() < 420_000);
    }

    @Test
    public void testStereoAndHighSampleRateInput() {
        AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
        addSpeechStereo48k(analyzer, 300);
        addMusicAdStereo48k(analyzer, 60);
        addSpeechStereo48k(analyzer, 300);
        List<AdSegment> segments = analyzer.getSegments();

        assertEquals(1, segments.size());
        assertTrue(segments.get(0).getStart() <= 310_000 && segments.get(0).getEnd() >= 350_000);
    }

    private void addSpeech(AdSegmentAnalyzer analyzer, int seconds) {
        analyzer.addPcm(ShortBuffer.wrap(synthSpeech(seconds, SAMPLE_RATE, 1)), 1, SAMPLE_RATE);
    }

    private void addMusicAd(AdSegmentAnalyzer analyzer, int seconds) {
        analyzer.addPcm(ShortBuffer.wrap(synthMusic(seconds, SAMPLE_RATE, 1)), 1, SAMPLE_RATE);
    }

    private void addSpeechStereo48k(AdSegmentAnalyzer analyzer, int seconds) {
        analyzer.addPcm(ShortBuffer.wrap(synthSpeech(seconds, 48000, 2)), 2, 48000);
    }

    private void addMusicAdStereo48k(AdSegmentAnalyzer analyzer, int seconds) {
        analyzer.addPcm(ShortBuffer.wrap(synthMusic(seconds, 48000, 2)), 2, 48000);
    }

    /**
     * Speech-like signal: quiet low-passed noise with a slowly varying envelope, moderate level.
     */
    private short[] synthSpeech(int seconds, int sampleRate, int channels) {
        int total = seconds * sampleRate;
        short[] samples = new short[total * channels];
        double lowpass = 0;
        for (int i = 0; i < total; i++) {
            double t = i / (double) sampleRate;
            double noise = random.nextGaussian();
            lowpass = 0.97 * lowpass + 0.03 * noise;
            double envelope = 0.6 + 0.2 * Math.sin(2 * Math.PI * 2.5 * t)
                    + 0.1 * Math.sin(2 * Math.PI * 0.13 * t);
            double pitch = 0.2 * Math.sin(2 * Math.PI * 140 * t);
            double value = (lowpass * 4 * envelope + pitch) * 0.15;
            short s = (short) (Math.max(-1, Math.min(1, value)) * 32767);
            for (int c = 0; c < channels; c++) {
                samples[i * channels + c] = s;
            }
        }
        return samples;
    }

    /**
     * Ad-like signal: loud, bright, sustained music bed of harmonic tones plus hi-hat noise.
     */
    private short[] synthMusic(int seconds, int sampleRate, int channels) {
        int total = seconds * sampleRate;
        short[] samples = new short[total * channels];
        for (int i = 0; i < total; i++) {
            double t = i / (double) sampleRate;
            double chord = Math.sin(2 * Math.PI * 440 * t)
                    + Math.sin(2 * Math.PI * 554 * t)
                    + Math.sin(2 * Math.PI * 659 * t)
                    + 0.5 * Math.sin(2 * Math.PI * 1319 * t)
                    + 0.4 * Math.sin(2 * Math.PI * 2637 * t);
            double beat = (t % 0.5) < 0.05 ? random.nextGaussian() * 0.6 : 0;
            double value = (chord * 0.16 + beat) * 0.85;
            short s = (short) (Math.max(-1, Math.min(1, value)) * 32767);
            for (int c = 0; c < channels; c++) {
                samples[i * channels + c] = s;
            }
        }
        return samples;
    }
}
