package de.danoeh.antennapod.net.download.service.episode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Chapter;

public final class AdChapterDetector {
    private static final String[] AD_KEYWORDS = {
            "sponsor", "sponsors", "sponsorship", "advert", "advertisement", "advertising",
            "ad break", "ads", "commercial", "promo", "werbung", "publicité", "anuncio"
    };

    private AdChapterDetector() {
    }

    public static List<AdSegment> detect(List<Chapter> chapters, long durationMs) {
        List<AdSegment> segments = new ArrayList<>();
        if (chapters == null || chapters.isEmpty()) {
            return segments;
        }
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            if (!isAdTitle(chapter.getTitle())) {
                continue;
            }
            long start = chapter.getStart();
            long end = i + 1 < chapters.size() ? chapters.get(i + 1).getStart() : durationMs;
            if (end > start) {
                segments.add(new AdSegment(start, end, 1.0f));
            }
        }
        return segments;
    }

    static boolean isAdTitle(String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.toLowerCase(Locale.ROOT).trim();
        for (String keyword : AD_KEYWORDS) {
            if (normalized.equals(keyword)) {
                return true;
            }
            if (normalized.startsWith(keyword + " ") || normalized.startsWith(keyword + ",")
                    || normalized.startsWith(keyword + ":") || normalized.startsWith(keyword + "s,")) {
                return true;
            }
            if (normalized.equals("ad") || normalized.equals("ad break") || normalized.equals("ad reads")) {
                return true;
            }
        }
        return false;
    }
}
