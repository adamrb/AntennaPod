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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import de.danoeh.antennapod.model.feed.AdSegment;

public final class AdSegmentDetector {
    private static final String TAG = "AdSegmentDetector";
    private static final long CODEC_TIMEOUT_US = 10000;
    private static final long PROGRESS_TIMEOUT_MS = 20000;
    private static final long TOTAL_DEADLINE_MS = 10 * 60 * 1000;
    private static final long MIN_CHUNK_US = 60_000_000L;
    private static final long PREROLL_US = 2_000_000L;
    private static final int MAX_THREADS = 8;

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
            return detectParallel(filePath, progressListener, trace);
        } catch (MediaCodec.CodecException e) {
            Log.w(TAG, "Codec failed in fast mode, retrying with conservative decode loop", e);
            trace(trace, "Codec error in fast mode (" + describeCodecException(e)
                    + "), retrying with conservative decode loop");
            try {
                return decodeAndAnalyzeSerial(filePath, progressListener, trace);
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

    private static List<AdSegment> detectParallel(String filePath, ProgressListener progressListener,
            StringBuilder trace) throws IOException, InterruptedException {
        MediaFormat format;
        long durationUs;
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(filePath);
            format = findAudioTrack(probe);
            if (format == null) {
                trace(trace, "No audio track found in file " + filePath);
                return new ArrayList<>();
            }
            durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
        } finally {
            probe.release();
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        boolean batchInput = "audio/mpeg".equals(mime);
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
        long windowUs = 0;
        if (sampleRate > 0) {
            int decimFactor = Math.max(1, Math.round((float) sampleRate / AdSegmentAnalyzer.TARGET_SAMPLE_RATE));
            windowUs = Math.round(1e6 * AdSegmentAnalyzer.FRAME_SIZE * AdSegmentAnalyzer.FRAMES_PER_WINDOW
                    * decimFactor / sampleRate);
        }
        int threads = 1;
        if (batchInput && durationUs > 0 && windowUs > 0) {
            threads = (int) Math.min(MAX_THREADS,
                    Math.min(Runtime.getRuntime().availableProcessors(), durationUs / MIN_CHUNK_US));
            threads = Math.max(1, threads);
        }
        trace(trace, "Audio track: " + mime + ", duration " + durationUs / 1000000
                + "s, threads: " + threads + ", batched input: " + batchInput);
        long startTime = System.currentTimeMillis();

        AtomicLongArray doneUs = new AtomicLongArray(threads);
        AtomicInteger lastPercent = new AtomicInteger(-1);
        Runnable progressUpdater = () -> {
            if (progressListener == null || durationUs <= 0) {
                return;
            }
            long total = 0;
            for (int i = 0; i < doneUs.length(); i++) {
                total += doneUs.get(i);
            }
            int percent = (int) (100 * total / durationUs);
            int previous = lastPercent.getAndSet(percent);
            if (percent != previous) {
                progressListener.onProgress(percent);
            }
        };

        List<List<double[]>> chunkWindows;
        double windowMs;
        if (threads == 1) {
            AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
            DecodeStats stats = new DecodeStats();
            decodeRange(filePath, 0, Long.MAX_VALUE, batchInput, analyzer, us -> {
                doneUs.set(0, us);
                progressUpdater.run();
            }, trace, stats);
            trace(trace, "Chunk stats: " + stats.summary());
            windowMs = analyzer.getWindowMs();
            chunkWindows = new ArrayList<>();
            chunkWindows.add(analyzer.getWindows());
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<AdSegmentAnalyzer>> futures = new ArrayList<>();
            List<DecodeStats> chunkStats = new ArrayList<>();
            long chunkUs = durationUs / threads / windowUs * windowUs;
            for (int i = 0; i < threads; i++) {
                final int chunkIndex = i;
                final long chunkStartUs = i * chunkUs;
                final long chunkEndUs = (i == threads - 1) ? Long.MAX_VALUE : (i + 1) * chunkUs;
                final DecodeStats stats = new DecodeStats();
                chunkStats.add(stats);
                futures.add(pool.submit(() -> {
                    AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
                    decodeRange(filePath, chunkStartUs, chunkEndUs, true, analyzer, us -> {
                        doneUs.set(chunkIndex, us - chunkStartUs);
                        progressUpdater.run();
                    }, null, stats);
                    return analyzer;
                }));
            }
            pool.shutdown();
            chunkWindows = new ArrayList<>();
            windowMs = 0;
            try {
                for (int i = 0; i < futures.size(); i++) {
                    AdSegmentAnalyzer analyzer = futures.get(i).get();
                    if (windowMs == 0) {
                        windowMs = analyzer.getWindowMs();
                    }
                    chunkWindows.add(analyzer.getWindows());
                    trace(trace, "Chunk " + i + " stats: " + chunkStats.get(i).summary());
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof MediaCodec.CodecException) {
                    throw (MediaCodec.CodecException) e.getCause();
                }
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw new IOException(e.getCause());
            } finally {
                pool.shutdownNow();
            }
        }

        List<double[]> merged = new ArrayList<>();
        for (List<double[]> windows : chunkWindows) {
            merged.addAll(windows);
        }
        List<AdSegment> segments = AdSegmentAnalyzer.getSegments(merged, windowMs);
        trace(trace, "Decode finished in " + (System.currentTimeMillis() - startTime) / 1000 + "s, "
                + segments.size() + " segment(s) found");
        return segments;
    }

    private interface RangeProgress {
        void onDecodedTo(long sampleTimeUs);
    }

    private static class DecodeStats {
        long extractorNs;
        long inputWaitNs;
        long outputWaitNs;
        long analyzerNs;
        long outputBuffers;

        String summary() {
            return String.format(java.util.Locale.US,
                    "extractor=%.1fs inputWait=%.1fs outputWait=%.1fs analyzer=%.1fs outputBuffers=%d",
                    extractorNs / 1e9, inputWaitNs / 1e9, outputWaitNs / 1e9, analyzerNs / 1e9, outputBuffers);
        }
    }

    private static MediaFormat findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat trackFormat = extractor.getTrackFormat(i);
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return trackFormat;
            }
        }
        return null;
    }

    private static void decodeRange(String filePath, long rangeStartUs, long rangeEndUs, boolean batchInput,
            AdSegmentAnalyzer analyzer, RangeProgress rangeProgress, StringBuilder trace,
            DecodeStats stats) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            MediaFormat format = findAudioTrack(extractor);
            if (format == null) {
                return;
            }
            if (rangeStartUs > 0) {
                extractor.seekTo(Math.max(0, rangeStartUs - PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long actualStartUs = extractor.getSampleTime();
                if (actualStartUs >= 0 && actualStartUs < rangeStartUs) {
                    analyzer.setSkipUs(rangeStartUs - actualStartUs);
                }
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            trace(trace, "Codec: " + codec.getName());

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat cachedOutputFormat = null;
            boolean inputDone = false;
            boolean outputDone = false;
            long startTime = System.currentTimeMillis();
            long lastProgressTime = startTime;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Analysis interrupted, aborting");
                    trace(trace, "ABORT: thread interrupted");
                    throw new IOException("interrupted");
                }
                long now = System.currentTimeMillis();
                if (now - lastProgressTime > PROGRESS_TIMEOUT_MS) {
                    Log.w(TAG, "Codec made no progress for " + PROGRESS_TIMEOUT_MS + "ms, aborting analysis");
                    trace(trace, "ABORT: codec made no progress for " + PROGRESS_TIMEOUT_MS + "ms");
                    throw new IOException("codec made no progress");
                }
                if (now - startTime > TOTAL_DEADLINE_MS) {
                    Log.w(TAG, "Analysis exceeded total deadline, aborting");
                    trace(trace, "ABORT: exceeded total deadline");
                    throw new IOException("exceeded total deadline");
                }
                boolean progressed = false;
                while (!inputDone) {
                    long t0 = System.nanoTime();
                    int inputIndex = codec.dequeueInputBuffer(0);
                    stats.inputWaitNs += System.nanoTime() - t0;
                    if (inputIndex < 0) {
                        break;
                    }
                    progressed = true;
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    int totalSize = 0;
                    long sampleTime = -1;
                    long t1 = System.nanoTime();
                    while (true) {
                        long nextSampleTime = extractor.getSampleTime();
                        if (nextSampleTime < 0 || nextSampleTime >= rangeEndUs) {
                            break;
                        }
                        int sampleSize = extractor.readSampleData(inputBuffer, totalSize);
                        if (sampleSize < 0) {
                            break;
                        }
                        if (sampleTime < 0) {
                            sampleTime = nextSampleTime;
                        }
                        totalSize += sampleSize;
                        extractor.advance();
                        if (!batchInput || inputBuffer.capacity() - totalSize < 4096) {
                            break;
                        }
                    }
                    stats.extractorNs += System.nanoTime() - t1;
                    if (totalSize == 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, totalSize, sampleTime, 0);
                        if (rangeProgress != null) {
                            rangeProgress.onDecodedTo(sampleTime);
                        }
                    }
                }
                long t2 = System.nanoTime();
                int outputIndex = codec.dequeueOutputBuffer(info, progressed ? 0 : CODEC_TIMEOUT_US);
                stats.outputWaitNs += System.nanoTime() - t2;
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true;
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        cachedOutputFormat = codec.getOutputFormat();
                        outputIndex = codec.dequeueOutputBuffer(info, 0);
                        continue;
                    }
                    stats.outputBuffers++;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        MediaFormat outputFormat = cachedOutputFormat != null
                                ? cachedOutputFormat : codec.getOutputFormat(outputIndex);
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        != AudioFormat.ENCODING_PCM_16BIT) {
                            codec.releaseOutputBuffer(outputIndex, false);
                            Log.w(TAG, "Unsupported PCM encoding, skipping analysis");
                            trace(trace, "ABORT: unsupported PCM encoding (not 16-bit)");
                            throw new IOException("unsupported PCM encoding");
                        }
                        int channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        long t3 = System.nanoTime();
                        analyzer.addPcm(outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer(),
                                channels, sampleRate);
                        stats.analyzerNs += System.nanoTime() - t3;
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (outputDone) {
                        break;
                    }
                    long t4 = System.nanoTime();
                    outputIndex = codec.dequeueOutputBuffer(info, 0);
                    stats.outputWaitNs += System.nanoTime() - t4;
                }
                if (progressed) {
                    lastProgressTime = now;
                }
            }
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

    private static List<AdSegment> decodeAndAnalyzeSerial(String filePath, ProgressListener progressListener,
            StringBuilder trace) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            MediaFormat format = findAudioTrack(extractor);
            if (format == null) {
                trace(trace, "No audio track found in file " + filePath);
                return new ArrayList<>();
            }
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = format.getString(MediaFormat.KEY_MIME);
            trace(trace, "Audio track: " + mime + ", duration " + durationUs / 1000000 + "s, conservative mode");
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            trace(trace, "Codec: " + codec.getName());

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
                        trace(trace, "ABORT: unsupported PCM encoding (not 16-bit)");
                        return new ArrayList<>();
                    }
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
