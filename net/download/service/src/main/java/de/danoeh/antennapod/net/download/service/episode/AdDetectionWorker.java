package de.danoeh.antennapod.net.download.service.episode;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.event.AdAnalysisProgressEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

public class AdDetectionWorker extends Worker {
    private static final String TAG = "AdDetectionWorker";
    private static final String WORK_NAME_PREFIX = "AdDetection-";

    public AdDetectionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, FeedMedia media) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdDetectionWorker.class)
                .setInputData(new androidx.work.Data.Builder()
                        .putLong(DownloadServiceInterface.WORK_DATA_MEDIA_ID, media.getId())
                        .build())
                .setConstraints(new Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + media.getId(), ExistingWorkPolicy.KEEP, request);
    }

    @Override
    @NonNull
    public Result doWork() {
        long mediaId = getInputData().getLong(DownloadServiceInterface.WORK_DATA_MEDIA_ID, 0);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || !media.localFileAvailable()) {
            return Result.failure();
        }
        try {
            List<AdSegment> segments = AdSegmentDetectionPipeline.detect(getApplicationContext(), media);
            if (isStopped()) {
                return Result.retry();
            }
            DBWriter.setAdSegments(media.getItemId(), segments);
            if (media.getItem() != null) {
                EventBus.getDefault().post(
                        new FeedItemEvent(Collections.singletonList(media.getItem()), false));
            }
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Ad detection failed", e);
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        } finally {
            EventBus.getDefault().post(AdAnalysisProgressEvent.done(media.getItemId()));
            NotificationManagerCompat.from(getApplicationContext()).cancel(R.id.notification_ad_analysis);
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        NotificationManagerCompat.from(getApplicationContext()).cancel(R.id.notification_ad_analysis);
    }
}
