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
    static final long MIN_SEGMENT_MS = 20000;
    static final long MAX_SEGMENT_MS = 240000;
    static final long MIN_EPISODE_MS = 5 * 60 * 1000;
    static final double BOUNDARY_THRESHOLD = 1.3;
    static final double DISTINCTNESS_THRESHOLD = 0.7;
    static final long MERGE_ADJACENT_MS = 10000;

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
        double[][] zScores = new double[numWindows][numFeatures];
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
            double mad = median(deviations.clone()) * 1.4826;
            if (mad < 1e-9) {
                mad = 1;
            }
            for (int w = 0; w < numWindows; w++) {
                zScores[w][f] = (values[w] - median) / mad;
            }
        }
        double[] scores = new double[numWindows];
        for (int w = 0; w < numWindows; w++) {
            double sum = 0;
            for (int f = 0; f < numFeatures; f++) {
                sum += Math.min(6, Math.abs(zScores[w][f]));
            }
            scores[w] = sum / numFeatures;
        }
        double[] smoothed = new double[numWindows];
        for (int i = 0; i < numWindows; i++) {
            int lo = Math.max(0, i - 1);
            int hi = Math.min(numWindows - 1, i + 1);
            double sum = 0;
            for (int j = lo; j <= hi; j++) {
                sum += scores[j];
            }
            smoothed[i] = sum / (hi - lo + 1);
        }

        List<Integer> boundaries = findBoundaryCandidates(smoothed);
        List<double[]> candidates = pairBoundaries(boundaries, smoothed, zScores, numFeatures, windowMs);
        addSustainedRunCandidates(candidates, smoothed, zScores, numFeatures, windowMs);
        return selectSegments(candidates, windowMs);
    }

    private static void addSustainedRunCandidates(List<double[]> candidates, double[] smoothed,
            double[][] zScores, int numFeatures, double windowMs) {
        int numWindows = smoothed.length;
        int runStart = -1;
        for (int w = 0; w <= numWindows; w++) {
            if (w < numWindows && smoothed[w] > BOUNDARY_THRESHOLD) {
                if (runStart < 0) {
                    runStart = w;
                }
            } else if (runStart >= 0) {
                long lengthMs = (long) ((w - runStart) * windowMs);
                if (lengthMs >= MIN_SEGMENT_MS && lengthMs <= MAX_SEGMENT_MS) {
                    double distinctness = interiorDistinctness(zScores, runStart, w, numFeatures);
                    if (distinctness >= DISTINCTNESS_THRESHOLD) {
                        double quality = distinctness * 2
                                + (smoothed[runStart] + smoothed[w - 1]) * 0.25
                                + Math.log(w - runStart) * 0.3;
                        candidates.add(new double[]{runStart, w, quality, distinctness});
                    }
                }
                runStart = -1;
            }
        }
    }

    private static List<Integer> findBoundaryCandidates(double[] smoothed) {
        List<Integer> boundaries = new ArrayList<>();
        for (int w = 1; w < smoothed.length - 1; w++) {
            if (smoothed[w] > BOUNDARY_THRESHOLD
                    && smoothed[w] >= smoothed[w - 1] && smoothed[w] >= smoothed[w + 1]) {
                if (!boundaries.isEmpty() && w - boundaries.get(boundaries.size() - 1) < 4) {
                    if (smoothed[w] > smoothed[boundaries.get(boundaries.size() - 1)]) {
                        boundaries.set(boundaries.size() - 1, w);
                    }
                } else {
                    boundaries.add(w);
                }
            }
        }
        return boundaries;
    }

    private static List<double[]> pairBoundaries(List<Integer> boundaries, double[] smoothed,
            double[][] zScores, int numFeatures, double windowMs) {
        List<double[]> candidates = new ArrayList<>();
        for (int i = 0; i < boundaries.size(); i++) {
            for (int j = i + 1; j < boundaries.size(); j++) {
                int start = boundaries.get(i);
                int end = boundaries.get(j);
                long lengthMs = (long) ((end - start) * windowMs);
                if (lengthMs < MIN_SEGMENT_MS) {
                    continue;
                }
                if (lengthMs > MAX_SEGMENT_MS) {
                    break;
                }
                double distinctness = interiorDistinctness(zScores, start, end, numFeatures);
                if (distinctness < DISTINCTNESS_THRESHOLD) {
                    continue;
                }
                double quality = distinctness * 2 + (smoothed[start] + smoothed[end]) * 0.25
                        + Math.log(end - start) * 0.3;
                candidates.add(new double[]{start, end, quality, distinctness});
            }
        }
        return candidates;
    }

    private static double interiorDistinctness(double[][] zScores, int start, int end, int numFeatures) {
        int length = end - start;
        if (length < 3) {
            return 0;
        }
        double total = 0;
        for (int f = 0; f < numFeatures; f++) {
            double[] values = new double[length];
            for (int w = start; w < end; w++) {
                values[w - start] = zScores[w][f];
            }
            total += Math.abs(median(values));
        }
        return total / numFeatures;
    }

    private static List<AdSegment> selectSegments(List<double[]> candidates, double windowMs) {
        candidates.sort((a, b) -> Double.compare(b[2], a[2]));
        List<double[]> chosen = new ArrayList<>();
        for (double[] candidate : candidates) {
            double[] overlapping = null;
            for (double[] other : chosen) {
                if (candidate[0] < other[1] && candidate[1] > other[0]) {
                    overlapping = other;
                    break;
                }
            }
            if (overlapping == null) {
                chosen.add(candidate);
            } else if ((Math.min(overlapping[1], candidate[1]) - Math.max(overlapping[0], candidate[0]))
                    * windowMs * 2 >= (candidate[1] - candidate[0]) * windowMs
                    && (Math.max(overlapping[1], candidate[1]) - Math.min(overlapping[0], candidate[0]))
                    * windowMs <= MAX_SEGMENT_MS) {
                overlapping[0] = Math.min(overlapping[0], candidate[0]);
                overlapping[1] = Math.max(overlapping[1], candidate[1]);
                overlapping[3] = Math.max(overlapping[3], candidate[3]);
            }
        }
        chosen.sort((a, b) -> Double.compare(a[0], b[0]));
        List<AdSegment> result = new ArrayList<>();
        for (double[] candidate : chosen) {
            float confidence = (float) Math.min(1.0, candidate[3] / 2.0);
            AdSegment segment = new AdSegment((long) (candidate[0] * windowMs),
                    (long) (candidate[1] * windowMs), confidence);
            AdSegment last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && segment.getStart() - last.getEnd() <= MERGE_ADJACENT_MS
                    && segment.getEnd() - last.getStart() <= MAX_SEGMENT_MS) {
                last.setEnd(segment.getEnd());
                last.setConfidence(Math.max(last.getConfidence(), segment.getConfidence()));
            } else {
                result.add(segment);
            }
        }
        return result;
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
