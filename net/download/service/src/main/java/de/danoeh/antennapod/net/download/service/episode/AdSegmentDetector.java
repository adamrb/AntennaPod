package de.danoeh.antennapod.net.download.service.episode;

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

    private AdSegmentDetector() {
    }

    public static List<AdSegment> detect(String filePath) {
        try {
            return decodeAndAnalyze(filePath);
        } catch (Exception e) {
            Log.e(TAG, "Ad segment analysis failed for " + filePath, e);
            return new ArrayList<>();
        }
    }

    private static List<AdSegment> decodeAndAnalyze(String filePath) throws IOException {
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

            AdSegmentAnalyzer analyzer = new AdSegmentAnalyzer();
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
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        analyzer.addPcm(outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer(),
                                channels, sampleRate);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
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
