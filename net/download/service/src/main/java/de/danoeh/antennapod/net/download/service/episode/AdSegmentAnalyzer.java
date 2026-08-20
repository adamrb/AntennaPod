package de.danoeh.antennapod.net.download.service.episode;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.model.feed.AdSegment;

public class AdSegmentAnalyzer {
    public static final int TARGET_SAMPLE_RATE = 16000;
    static final int FRAME_SIZE = 1024;
    static final int FRAMES_PER_WINDOW = 16;
    static final long MIN_SEGMENT_MS = 15000;
    static final long MAX_SEGMENT_MS = 300000;
    static final long MERGE_GAP_MS = 120000;
    static final long MIN_EPISODE_MS = 5 * 60 * 1000;
    static final double SCORE_THRESHOLD = 1.2;
    static final double MERGE_GAP_SCORE = 0.75 * SCORE_THRESHOLD;
    static final int SMOOTH_WINDOWS = 15;

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
    private double effectiveSampleRate = TARGET_SAMPLE_RATE;

    public AdSegmentAnalyzer() {
        for (int i = 0; i < FRAME_SIZE; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
        }
    }

    public void addPcm(ShortBuffer samples, int channels, int sampleRate) {
        decimFactor = Math.max(1, Math.round((float) sampleRate / TARGET_SAMPLE_RATE));
        effectiveSampleRate = (double) sampleRate / decimFactor;
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

    public List<AdSegment> getSegments() {
        double windowMs = 1000.0 * FRAME_SIZE * FRAMES_PER_WINDOW / effectiveSampleRate;
        if (windows.size() * windowMs < MIN_EPISODE_MS) {
            return new ArrayList<>();
        }
        return findSegments(windows, windowMs);
    }

    static List<AdSegment> findSegments(List<double[]> windows, double windowMs) {
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

        double[] smoothed = new double[numWindows];
        for (int i = 0; i < numWindows; i++) {
            int lo = Math.max(0, i - SMOOTH_WINDOWS / 2);
            int hi = Math.min(numWindows - 1, i + SMOOTH_WINDOWS / 2);
            double sum = 0;
            for (int j = lo; j <= hi; j++) {
                sum += scores[j];
            }
            smoothed[i] = sum / (hi - lo + 1);
        }

        boolean[] adLike = new boolean[numWindows];
        for (int w = 0; w < numWindows; w++) {
            adLike[w] = smoothed[w] > SCORE_THRESHOLD;
        }

        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        for (int w = 0; w <= numWindows; w++) {
            if (w < numWindows && adLike[w]) {
                if (runStart < 0) {
                    runStart = w;
                }
            } else if (runStart >= 0) {
                runs.add(new int[]{runStart, w});
                runStart = -1;
            }
        }

        List<int[]> merged = new ArrayList<>();
        for (int[] run : runs) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && (run[0] - last[1]) * windowMs <= MERGE_GAP_MS
                    && gapScoreStaysElevated(smoothed, last[1], run[0])) {
                last[1] = run[1];
            } else {
                merged.add(run);
            }
        }

        List<AdSegment> result = new ArrayList<>();
        for (int[] run : merged) {
            AdSegment segment = makeSegment(run[0], run[1], smoothed, windowMs);
            long length = segment.getEnd() - segment.getStart();
            if (length >= MIN_SEGMENT_MS && length <= MAX_SEGMENT_MS) {
                result.add(segment);
            }
        }
        return result;
    }

    private static boolean gapScoreStaysElevated(double[] smoothed, int gapStart, int gapEnd) {
        for (int w = gapStart; w < gapEnd; w++) {
            if (smoothed[w] < MERGE_GAP_SCORE) {
                return false;
            }
        }
        return true;
    }

    private static AdSegment makeSegment(int startWindow, int endWindow, double[] scores, double windowMs) {
        double sum = 0;
        for (int w = startWindow; w < endWindow; w++) {
            sum += scores[w];
        }
        double meanScore = sum / (endWindow - startWindow);
        float confidence = (float) Math.min(1.0, meanScore / (2 * SCORE_THRESHOLD));
        return new AdSegment((long) (startWindow * windowMs), (long) (endWindow * windowMs), confidence);
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        if (values.length % 2 == 0) {
            return (values[mid - 1] + values[mid]) / 2;
        }
        return values[mid];
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
