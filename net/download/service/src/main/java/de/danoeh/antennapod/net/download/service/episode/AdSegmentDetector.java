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
        return detect(filePath, progressListener, null);
    }

    public static List<AdSegment> detect(String filePath, ProgressListener progressListener, StringBuilder trace) {
        try {
            return decodeAndAnalyze(filePath, progressListener, trace, true);
        } catch (MediaCodec.CodecException e) {
            Log.w(TAG, "Codec failed in fast mode, retrying with conservative decode loop", e);
            trace(trace, "Codec error in fast mode (" + describeCodecException(e)
                    + "), retrying with conservative decode loop");
            try {
                return decodeAndAnalyze(filePath, progressListener, trace, false);
            } catch (Exception retryException) {
                Log.e(TAG, "Ad segment analysis failed for " + filePath, retryException);
                trace(trace, "EXCEPTION: " + describeException(retryException));
                return new ArrayList<>();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ad segment analysis failed for " + filePath, e);
            trace(trace, "EXCEPTION: " + describeException(e));
            return new ArrayList<>();
        }
    }

    private static String describeException(Exception e) {
        if (e instanceof MediaCodec.CodecException) {
            return describeCodecException((MediaCodec.CodecException) e);
        }
        return e.toString();
    }

    private static String describeCodecException(MediaCodec.CodecException e) {
        return e + " diagnostic=" + e.getDiagnosticInfo()
                + " recoverable=" + e.isRecoverable() + " transient=" + e.isTransient();
    }

    private static void trace(StringBuilder trace, String message) {
        if (trace != null) {
            trace.append(message).append('\n');
        }
    }

    private static List<AdSegment> decodeAndAnalyze(String filePath, ProgressListener progressListener,
            StringBuilder trace, boolean fastMode) throws IOException {
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
                trace(trace, "No audio track found in file " + filePath);
                return new ArrayList<>();
            }
            extractor.selectTrack(trackIndex);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = format.getString(MediaFormat.KEY_MIME);
            trace(trace, "Audio track: " + mime + ", duration " + durationUs / 1000000 + "s");
            codec = MediaCodec.createDecoderByType(mime);
            boolean batchInput = fastMode && "audio/mpeg".equals(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            trace(trace, "Codec: " + codec.getName() + ", fast mode: " + fastMode
                    + ", batched input: " + batchInput);

            AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat cachedOutputFormat = null;
            boolean inputDone = false;
            boolean outputDone = false;
            long startTime = System.currentTimeMillis();
            long lastProgressTime = startTime;
            int lastReportedPercent = -1;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Analysis interrupted, aborting");
                    trace(trace, "ABORT: thread interrupted");
                    return new ArrayList<>();
                }
                long now = System.currentTimeMillis();
                if (now - lastProgressTime > PROGRESS_TIMEOUT_MS) {
                    Log.w(TAG, "Codec made no progress for " + PROGRESS_TIMEOUT_MS + "ms, aborting analysis");
                    trace(trace, "ABORT: codec made no progress for " + PROGRESS_TIMEOUT_MS + "ms");
                    return new ArrayList<>();
                }
                if (now - startTime > TOTAL_DEADLINE_MS) {
                    Log.w(TAG, "Analysis exceeded total deadline, aborting");
                    trace(trace, "ABORT: exceeded total deadline");
                    return new ArrayList<>();
                }
                boolean progressed = false;
                while (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(fastMode ? 0 : CODEC_TIMEOUT_US);
                    if (inputIndex < 0) {
                        break;
                    }
                    progressed = true;
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    int totalSize = 0;
                    long sampleTime = -1;
                    while (true) {
                        int sampleSize = extractor.readSampleData(inputBuffer, totalSize);
                        if (sampleSize < 0) {
                            break;
                        }
                        if (sampleTime < 0) {
                            sampleTime = extractor.getSampleTime();
                        }
                        totalSize += sampleSize;
                        extractor.advance();
                        if (!batchInput || inputBuffer.capacity() - totalSize < 4096) {
                            break;
                        }
                    }
                    if (totalSize == 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, totalSize, sampleTime, 0);
                        if (progressListener != null && durationUs > 0) {
                            int percent = (int) (100 * sampleTime / durationUs);
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent;
                                progressListener.onProgress(percent);
                            }
                        }
                    }
                    if (!fastMode) {
                        break;
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, progressed && fastMode ? 0 : CODEC_TIMEOUT_US);
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true;
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        cachedOutputFormat = codec.getOutputFormat();
                        outputIndex = codec.dequeueOutputBuffer(info, 0);
                        continue;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    boolean unsupportedEncoding = false;
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        MediaFormat outputFormat = cachedOutputFormat != null
                                ? cachedOutputFormat : codec.getOutputFormat(outputIndex);
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
                        trace(trace, "ABORT: unsupported PCM encoding (not 16-bit)");
                        return new ArrayList<>();
                    }
                    if (outputDone || !fastMode) {
                        break;
                    }
                    outputIndex = codec.dequeueOutputBuffer(info, 0);
                }
                if (progressed) {
                    lastProgressTime = now;
                }
            }
            List<AdSegment> segments = analyzer.getSegments();
            trace(trace, "Decode finished in " + (System.currentTimeMillis() - startTime) / 1000 + "s, "
                    + segments.size() + " segment(s) found");
            return segments;
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
