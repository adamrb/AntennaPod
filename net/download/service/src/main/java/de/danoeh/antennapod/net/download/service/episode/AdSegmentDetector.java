package de.danoeh.antennapod.net.download.service.episode;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;

public final class AdSegmentDetector {
    private static final String TAG = "AdSegmentDetector";
    private static final long CODEC_TIMEOUT_US = 10000;
    private static final long PROGRESS_TIMEOUT_MS = 20000;
    private static final long TOTAL_DEADLINE_MS = 10 * 60 * 1000;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private AdSegmentDetector() {
    }

    public static List<AdSegment> detect(String filePath, ProgressListener progressListener) {
        try {
            return decodeAndAnalyze(filePath, progressListener);
        } catch (Exception e) {
            Log.e(TAG, "Ad segment analysis failed for " + filePath, e);
            return new ArrayList<>();
        }
    }

    private static List<AdSegment> decodeAndAnalyze(String filePath, ProgressListener progressListener)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = trackFormat;
                    break;
                }
            }
            if (trackIndex < 0) {
                return new ArrayList<>();
            }
            extractor.selectTrack(trackIndex);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long startTime = System.currentTimeMillis();
            long lastProgressTime = startTime;
            int lastReportedPercent = -1;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Analysis interrupted, aborting");
                    return new ArrayList<>();
                }
                long now = System.currentTimeMillis();
                if (now - lastProgressTime > PROGRESS_TIMEOUT_MS) {
                    Log.w(TAG, "Codec made no progress for " + PROGRESS_TIMEOUT_MS + "ms, aborting analysis");
                    return new ArrayList<>();
                }
                if (now - startTime > TOTAL_DEADLINE_MS) {
                    Log.w(TAG, "Analysis exceeded total deadline, aborting");
                    return new ArrayList<>();
                }
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                            if (progressListener != null && durationUs > 0) {
                                int percent = (int) (100 * sampleTime / durationUs);
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent;
                                    progressListener.onProgress(percent);
                                }
                            }
                        }
                        lastProgressTime = now;
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex >= 0) {
                    lastProgressTime = now;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    boolean unsupportedEncoding = false;
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        MediaFormat outputFormat = codec.getOutputFormat(outputIndex);
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        != AudioFormat.ENCODING_PCM_16BIT) {
                            unsupportedEncoding = true;
                        } else {
                            int channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            outputBuffer.position(info.offset);
                            outputBuffer.limit(info.offset + info.size);
                            analyzer.addPcm(outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer(),
                                    channels, sampleRate);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (unsupportedEncoding) {
                        Log.w(TAG, "Unsupported PCM encoding, skipping analysis");
                        return new ArrayList<>();
                    }
                }
            }
            return analyzer.getSegments();
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignore) {
                    // Codec was not in a stoppable state
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
