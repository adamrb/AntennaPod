package de.danoeh.antennapod.net.download.service.episode;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;

public final class AdSegmentDetector {
    private static final String TAG = "AdSegmentDetector";

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 1024;
    private static final int FRAMES_PER_WINDOW = 16;
    private static final long WINDOW_MS = 1000L * FRAME_SIZE * FRAMES_PER_WINDOW / TARGET_SAMPLE_RATE;
    private static final long MIN_SEGMENT_MS = 15000;
    private static final long MAX_SEGMENT_MS = 180000;
    private static final long MERGE_GAP_MS = 6000;
    private static final long MIN_EPISODE_MS = 5 * 60 * 1000;
    private static final double SCORE_THRESHOLD = 2.0;
    private static final long CODEC_TIMEOUT_US = 10000;

    private AdSegmentDetector() {
    }

    public static List<AdSegment> detect(String filePath) {
        try {
            List<double[]> windows = extractFeatureWindows(filePath);
            if (windows.size() * WINDOW_MS < MIN_EPISODE_MS) {
                return new ArrayList<>();
            }
            return findSegments(windows);
        } catch (Exception e) {
            Log.e(TAG, "Ad segment analysis failed for " + filePath, e);
            return new ArrayList<>();
        }
    }

    private static List<double[]> extractFeatureWindows(String filePath) throws IOException {
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
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            FeatureAccumulator accumulator = new FeatureAccumulator();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
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
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        MediaFormat outputFormat = codec.getOutputFormat(outputIndex);
                        int channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        accumulator.addPcm(outputBuffer, info, channels, sampleRate);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            return accumulator.getWindows();
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

    private static List<AdSegment> findSegments(List<double[]> windows) {
        int numWindows = windows.size();
        int numFeatures = windows.get(0).length;
        double[] scores = new double[numWindows];
        for (int f = 0; f < numFeatures; f++) {
            double[] values = new double[numWindows];
            for (int w = 0; w < numWindows; w++) {
                values[w] = windows.get(w)[f];
            }
            double median = median(values.clone());
            double[] deviations = new double[numWindows];
            for (int w = 0; w < numWindows; w++) {
                deviations[w] = Math.abs(values[w] - median);
            }
            double mad = median(deviations.clone());
            if (mad < 1e-9) {
                continue;
            }
            for (int w = 0; w < numWindows; w++) {
                scores[w] += Math.min(6, deviations[w] / (mad * 1.4826)) / numFeatures;
            }
        }

        boolean[] adLike = new boolean[numWindows];
        for (int w = 0; w < numWindows; w++) {
            adLike[w] = scores[w] > SCORE_THRESHOLD;
        }
        medianSmooth(adLike);

        List<AdSegment> segments = new ArrayList<>();
        int runStart = -1;
        for (int w = 0; w <= numWindows; w++) {
            if (w < numWindows && adLike[w]) {
                if (runStart < 0) {
                    runStart = w;
                }
            } else if (runStart >= 0) {
                segments.add(makeSegment(runStart, w, scores));
                runStart = -1;
            }
        }

        List<AdSegment> merged = new ArrayList<>();
        for (AdSegment segment : segments) {
            AdSegment last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && segment.getStart() - last.getEnd() <= MERGE_GAP_MS) {
                last.setEnd(segment.getEnd());
                last.setConfidence(Math.max(last.getConfidence(), segment.getConfidence()));
            } else {
                merged.add(segment);
            }
        }

        List<AdSegment> result = new ArrayList<>();
        for (AdSegment segment : merged) {
            long length = segment.getEnd() - segment.getStart();
            if (length >= MIN_SEGMENT_MS && length <= MAX_SEGMENT_MS) {
                result.add(segment);
            }
        }
        return result;
    }

    private static AdSegment makeSegment(int startWindow, int endWindow, double[] scores) {
        double sum = 0;
        for (int w = startWindow; w < endWindow; w++) {
            sum += scores[w];
        }
        double meanScore = sum / (endWindow - startWindow);
        float confidence = (float) Math.min(1.0, meanScore / (2 * SCORE_THRESHOLD));
        return new AdSegment(startWindow * WINDOW_MS, endWindow * WINDOW_MS, confidence);
    }

    private static void medianSmooth(boolean[] mask) {
        boolean[] original = mask.clone();
        for (int i = 1; i < mask.length - 1; i++) {
            int count = (original[i - 1] ? 1 : 0) + (original[i] ? 1 : 0) + (original[i + 1] ? 1 : 0);
            mask[i] = count >= 2;
        }
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        if (values.length % 2 == 0) {
            return (values[mid - 1] + values[mid]) / 2;
        }
        return values[mid];
    }

    private static class FeatureAccumulator {
        private final List<double[]> windows = new ArrayList<>();
        private final float[] frame = new float[FRAME_SIZE];
        private final float[] fftReal = new float[FRAME_SIZE];
        private final float[] fftImag = new float[FRAME_SIZE];
        private final float[] hann = new float[FRAME_SIZE];
        private final float[] magnitude = new float[FRAME_SIZE / 2];
        private final float[] previousMagnitude = new float[FRAME_SIZE / 2];
        private int framePos = 0;
        private double decimBuffer = 0;
        private int decimCount = 0;
        private int decimFactor = 1;
        private double windowEnergy = 0;
        private double windowCentroid = 0;
        private double windowFlatness = 0;
        private double windowFlux = 0;
        private int windowFrameCount = 0;
        private boolean hasPreviousMagnitude = false;

        FeatureAccumulator() {
            for (int i = 0; i < FRAME_SIZE; i++) {
                hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
            }
        }

        List<double[]> getWindows() {
            return windows;
        }

        void addPcm(ByteBuffer buffer, MediaCodec.BufferInfo info, int channels, int sampleRate) {
            decimFactor = Math.max(1, Math.round((float) sampleRate / TARGET_SAMPLE_RATE));
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            ShortBuffer samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
            int frames = samples.remaining() / channels;
            for (int i = 0; i < frames; i++) {
                double mono = 0;
                for (int c = 0; c < channels; c++) {
                    mono += samples.get(i * channels + c);
                }
                mono /= channels * 32768.0;
                decimBuffer += mono;
                decimCount++;
                if (decimCount >= decimFactor) {
                    pushSample((float) (decimBuffer / decimCount));
                    decimBuffer = 0;
                    decimCount = 0;
                }
            }
        }

        private void pushSample(float sample) {
            frame[framePos++] = sample;
            if (framePos >= FRAME_SIZE) {
                framePos = 0;
                processFrame();
            }
        }

        private void processFrame() {
            double energy = 0;
            for (int i = 0; i < FRAME_SIZE; i++) {
                energy += frame[i] * frame[i];
                fftReal[i] = frame[i] * hann[i];
                fftImag[i] = 0;
            }
            fft(fftReal, fftImag);

            double magnitudeSum = 0;
            double weightedSum = 0;
            double logSum = 0;
            double flux = 0;
            for (int i = 0; i < FRAME_SIZE / 2; i++) {
                float mag = (float) Math.sqrt(fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]);
                magnitude[i] = mag;
                magnitudeSum += mag;
                weightedSum += mag * i;
                logSum += Math.log(mag + 1e-12);
                if (hasPreviousMagnitude) {
                    float diff = mag - previousMagnitude[i];
                    if (diff > 0) {
                        flux += diff;
                    }
                }
            }
            System.arraycopy(magnitude, 0, previousMagnitude, 0, FRAME_SIZE / 2);
            hasPreviousMagnitude = true;

            double centroid = magnitudeSum > 1e-12 ? weightedSum / magnitudeSum : 0;
            double geometricMean = Math.exp(logSum / (FRAME_SIZE / 2));
            double arithmeticMean = magnitudeSum / (FRAME_SIZE / 2);
            double flatness = arithmeticMean > 1e-12 ? geometricMean / arithmeticMean : 0;

            windowEnergy += 10 * Math.log10(energy / FRAME_SIZE + 1e-12);
            windowCentroid += centroid;
            windowFlatness += flatness;
            windowFlux += magnitudeSum > 1e-12 ? flux / magnitudeSum : 0;
            windowFrameCount++;
            if (windowFrameCount >= FRAMES_PER_WINDOW) {
                windows.add(new double[]{
                        windowEnergy / windowFrameCount,
                        windowCentroid / windowFrameCount,
                        windowFlatness / windowFrameCount,
                        windowFlux / windowFrameCount});
                windowEnergy = 0;
                windowCentroid = 0;
                windowFlatness = 0;
                windowFlux = 0;
                windowFrameCount = 0;
            }
        }

        private static void fft(float[] real, float[] imag) {
            int n = real.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    float tmp = real[i];
                    real[i] = real[j];
                    real[j] = tmp;
                    tmp = imag[i];
                    imag[i] = imag[j];
                    imag[j] = tmp;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double angle = -2 * Math.PI / len;
                float stepReal = (float) Math.cos(angle);
                float stepImag = (float) Math.sin(angle);
                for (int i = 0; i < n; i += len) {
                    float twiddleReal = 1;
                    float twiddleImag = 0;
                    for (int j = 0; j < len / 2; j++) {
                        int even = i + j;
                        int odd = i + j + len / 2;
                        float oddReal = real[odd] * twiddleReal - imag[odd] * twiddleImag;
                        float oddImag = real[odd] * twiddleImag + imag[odd] * twiddleReal;
                        real[odd] = real[even] - oddReal;
                        imag[odd] = imag[even] - oddImag;
                        real[even] += oddReal;
                        imag[even] += oddImag;
                        float newTwiddleReal = twiddleReal * stepReal - twiddleImag * stepImag;
                        twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal;
                        twiddleReal = newTwiddleReal;
                    }
                }
            }
        }
    }
}
