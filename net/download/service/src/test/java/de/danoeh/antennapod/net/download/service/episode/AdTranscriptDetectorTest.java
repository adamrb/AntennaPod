package de.danoeh.antennapod.net.download.service.episode;

import org.junit.Test;

import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdTranscriptDetectorTest {

    @Test
    public void testDetectsSponsorReadCluster() {
        Transcript transcript = new Transcript();
        addTalk(transcript, 0, 120_000);
        transcript.addSegment(new TranscriptSegment(120_000, 128_000,
                "this episode is brought to you by ExampleVPN use promo code podcast", null));
        transcript.addSegment(new TranscriptSegment(128_000, 136_000,
                "go to examplevpn dot com slash podcast for twenty percent off your first order", null));
        transcript.addSegment(new TranscriptSegment(136_000, 144_000,
                "they offer a free trial and you can cancel anytime terms apply", null));
        transcript.addSegment(new TranscriptSegment(144_000, 152_000,
                "thanks to our sponsors and now back to the show", null));
        addTalk(transcript, 160_000, 600_000);

        List<AdSegment> segments = AdTranscriptDetector.detect(transcript, 600_000);
        assertEquals(1, segments.size());
        AdSegment segment = segments.get(0);
        assertTrue("start " + segment.getStart(), segment.getStart() <= 120_000);
        assertTrue("end " + segment.getEnd(), segment.getEnd() >= 150_000);
        assertEquals(0.9f, segment.getConfidence(), 0.001f);
    }

    @Test
    public void testIgnoresPlainConversation() {
        Transcript transcript = new Transcript();
        addTalk(transcript, 0, 600_000);
        assertTrue(AdTranscriptDetector.detect(transcript, 600_000).isEmpty());
    }

    @Test
    public void testSingleMentionDoesNotTrigger() {
        Transcript transcript = new Transcript();
        addTalk(transcript, 0, 300_000);
        transcript.addSegment(new TranscriptSegment(300_000, 308_000,
                "I got a discount on my new laptop last week", null));
        addTalk(transcript, 310_000, 600_000);
        assertTrue(AdTranscriptDetector.detect(transcript, 600_000).isEmpty());
    }

    @Test
    public void testNullTranscript() {
        assertTrue(AdTranscriptDetector.detect(null, 600_000).isEmpty());
    }

    private void addTalk(Transcript transcript, long fromMs, long toMs) {
        for (long t = fromMs; t < toMs; t += 8000) {
            transcript.addSegment(new TranscriptSegment(t, t + 8000,
                    "and then we talked about the situation and what it means for everyone", null));
        }
    }
}
