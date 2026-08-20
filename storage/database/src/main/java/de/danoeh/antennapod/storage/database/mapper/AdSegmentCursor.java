package de.danoeh.antennapod.storage.database.mapper;

import android.database.Cursor;
import android.database.CursorWrapper;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

public class AdSegmentCursor extends CursorWrapper {
    private final int indexId;
    private final int indexStart;
    private final int indexEnd;
    private final int indexConfidence;

    public AdSegmentCursor(Cursor cursor) {
        super(cursor);
        indexId = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
        indexStart = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_START);
        indexEnd = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_END);
        indexConfidence = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_CONFIDENCE);
    }

    @NonNull
    public AdSegment getAdSegment() {
        AdSegment segment = new AdSegment(
                getLong(indexStart),
                getLong(indexEnd),
                getFloat(indexConfidence));
        segment.setId(getLong(indexId));
        return segment;
    }
}
