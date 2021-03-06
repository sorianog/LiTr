/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr.transcoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import com.linkedin.android.litr.codec.Decoder;
import com.linkedin.android.litr.codec.Encoder;
import com.linkedin.android.litr.codec.Frame;
import com.linkedin.android.litr.exception.TrackTranscoderException;
import com.linkedin.android.litr.io.MediaSource;
import com.linkedin.android.litr.io.MediaTarget;
import com.linkedin.android.litr.render.GlVideoRenderer;
import com.linkedin.android.litr.render.VideoRenderer;

/**
 * Transcoder that processes video tracks.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class VideoTrackTranscoder extends TrackTranscoder {
    private static final String TAG = VideoTrackTranscoder.class.getSimpleName();

    private static final long MILLISECONDS_IN_SECOND = 1000;

    @VisibleForTesting int lastExtractFrameResult;
    @VisibleForTesting int lastDecodeFrameResult;
    @VisibleForTesting int lastEncodeFrameResult;

    @VisibleForTesting GlVideoRenderer renderer;

    @NonNull private MediaFormat sourceVideoFormat;
    @NonNull private MediaFormat targetVideoFormat;

    VideoTrackTranscoder(@NonNull MediaSource mediaSource,
                         int sourceTrack,
                         @NonNull MediaTarget mediaTarget,
                         int targetTrack,
                         @NonNull MediaFormat targetFormat,
                         @NonNull VideoRenderer renderer,
                         @NonNull Decoder decoder,
                         @NonNull Encoder encoder) throws TrackTranscoderException {
        super(mediaSource, sourceTrack, mediaTarget, targetTrack, targetFormat, decoder, encoder);

        lastExtractFrameResult = RESULT_FRAME_PROCESSED;
        lastDecodeFrameResult = RESULT_FRAME_PROCESSED;
        lastEncodeFrameResult = RESULT_FRAME_PROCESSED;

        targetVideoFormat = targetFormat;

        if (!(renderer instanceof GlVideoRenderer)) {
            throw new IllegalArgumentException("Cannot use non-OpenGL video renderer in " + VideoTrackTranscoder.class.getSimpleName());
        }
        this.renderer = (GlVideoRenderer) renderer;

        initCodecs();
    }

    private void initCodecs() throws TrackTranscoderException {
        sourceVideoFormat = mediaSource.getTrackFormat(sourceTrack);
        if (sourceVideoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            int sourceFrameRate = sourceVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            targetVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, sourceFrameRate);
        }
        // extract and store the duration, we will use it to track transcoding progress
        if (sourceVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            duration = sourceVideoFormat.getLong(MediaFormat.KEY_DURATION);
            targetVideoFormat.setLong(MediaFormat.KEY_DURATION, (long) duration);
        }
        // clear the rotation flag, we don't want any auto-rotation issues
        int rotation = 0;
        if (sourceVideoFormat.containsKey(KEY_ROTATION)) {
            rotation = sourceVideoFormat.getInteger(KEY_ROTATION);
        }
        float aspectRatio = 1;
        if (targetVideoFormat.containsKey(MediaFormat.KEY_WIDTH) && targetVideoFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            aspectRatio = (float) targetVideoFormat.getInteger(MediaFormat.KEY_WIDTH) / targetVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        }

        encoder.init(targetFormat);
        renderer.init(encoder.createInputSurface(), rotation, aspectRatio);
        decoder.init(sourceVideoFormat, renderer.getInputSurface());
    }

    @Override
    public void start() throws TrackTranscoderException {
        mediaSource.selectTrack(sourceTrack);
        encoder.start();
        decoder.start();
    }

    @Override
    public void stop() {
        encoder.stop();
        encoder.release();

        decoder.stop();
        decoder.release();

        renderer.release();
    }

    @Override
    public int processNextFrame() throws TrackTranscoderException {
        if (!encoder.isRunning() || !decoder.isRunning()) {
            // can't do any work
            return ERROR_TRANSCODER_NOT_RUNNING;
        }
        int result = RESULT_FRAME_PROCESSED;

        // extract the frame from the incoming stream and send it to the decoder
        if (lastExtractFrameResult != RESULT_EOS_REACHED) {
            lastExtractFrameResult = extractAndEnqueueInputFrame();
        }

        // receive the decoded frame and send it to the encoder by rendering it on encoder's input surface
        if (lastDecodeFrameResult != RESULT_EOS_REACHED) {
            lastDecodeFrameResult = resizeDecodedInputFrame();
        }

        // get the encoded frame and write it into the target file
        if (lastEncodeFrameResult != RESULT_EOS_REACHED) {
            lastEncodeFrameResult = writeEncodedOutputFrame();
        }

        if (lastEncodeFrameResult == RESULT_OUTPUT_MEDIA_FORMAT_CHANGED) {
            result = RESULT_OUTPUT_MEDIA_FORMAT_CHANGED;
        }

        if (lastExtractFrameResult == RESULT_EOS_REACHED
            && lastDecodeFrameResult == RESULT_EOS_REACHED
            && lastEncodeFrameResult == RESULT_EOS_REACHED) {
            result = RESULT_EOS_REACHED;
        }

        return result;
    }

    private int extractAndEnqueueInputFrame() throws TrackTranscoderException {
        int extractFrameResult = RESULT_FRAME_PROCESSED;

        int selectedTrack = mediaSource.getSampleTrackIndex();
        if (selectedTrack == sourceTrack || selectedTrack == NO_SELECTED_TRACK) {
            int tag = decoder.dequeueInputFrame(0);
            if (tag >= 0) {
                Frame frame = decoder.getInputFrame(tag);
                if (frame == null) {
                    throw new TrackTranscoderException(TrackTranscoderException.Error.NO_FRAME_AVAILABLE);
                }
                int bytesRead = mediaSource.readSampleData(frame.buffer, 0);
                // TODO here we are assuming that MediaSource will always produce bytes, which may not always be the case
                if (bytesRead > 0) {
                    long sampleTime = mediaSource.getSampleTime();
                    int sampleFlags = mediaSource.getSampleFlags();
                    frame.bufferInfo.set(0, bytesRead, sampleTime, sampleFlags);
                    decoder.queueInputFrame(frame);
                    mediaSource.advance();
                    //Log.d(TAG, "Sample time: " + sampleTime + ", source bytes read: " + bytesRead);
                } else {
                    frame.bufferInfo.set(0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    decoder.queueInputFrame(frame);
                    extractFrameResult = RESULT_EOS_REACHED;
                    Log.d(TAG, "EoS reached on the input stream");
                }
            } else {
                switch (tag) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        //Log.d(TAG, "Will try getting decoder input buffer later");
                        break;
                    default:
                        Log.e(TAG, "Unhandled value " + tag + " when decoding an input frame");
                        break;
                }
            }
        }

        return extractFrameResult;
    }

    private int resizeDecodedInputFrame() throws TrackTranscoderException {
        int decodeFrameResult = RESULT_FRAME_PROCESSED;

        int tag = decoder.dequeueOutputFrame(0);
        if (tag >= 0) {
            Frame frame = decoder.getOutputFrame(tag);
            if (frame == null) {
                throw new TrackTranscoderException(TrackTranscoderException.Error.NO_FRAME_AVAILABLE);
            }
            if ((frame.bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "EoS on decoder output stream");
                decoder.releaseOutputFrame(tag, false);
                encoder.signalEndOfInputStream();
                decodeFrameResult = RESULT_EOS_REACHED;
            } else {
                decoder.releaseOutputFrame(tag, true);
                renderer.renderFrame(null, frame.bufferInfo.presentationTimeUs * MILLISECONDS_IN_SECOND);
            }
        } else {
            switch (tag) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    // Log.d(TAG, "Will try getting decoder output later");
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    Log.d(TAG, "Decoder output format changed: " + outputFormat);
                    break;
                default:
                    Log.e(TAG, "Unhandled value " + tag + " when receiving decoded input frame");
                    break;
            }
        }

        return decodeFrameResult;
    }

    private int writeEncodedOutputFrame() throws TrackTranscoderException {
        int encodeFrameResult = RESULT_FRAME_PROCESSED;

        int index = encoder.dequeueOutputFrame(0);
        if (index >= 0) {
            Frame frame = encoder.getOutputFrame(index);
            if (frame == null) {
                throw new TrackTranscoderException(TrackTranscoderException.Error.NO_FRAME_AVAILABLE);
            }

            if (frame.bufferInfo.size > 0
                && (frame.bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                mediaMuxer.writeSampleData(targetTrack, frame.buffer, frame.bufferInfo);
                progress = ((float) frame.bufferInfo.presentationTimeUs) / duration;
            }

            if ((frame.bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "Encoder produced EoS, we are done");
                progress = 1.0f;
                encodeFrameResult = RESULT_EOS_REACHED;
            }

            encoder.releaseOutputFrame(index);
        } else {
            switch (index) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    // Log.d(TAG, "Will try getting encoder output buffer later");
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    // TODO for now, we assume that we only get one media format as a first buffer
                    MediaFormat outputMediaFormat = encoder.getOutputFormat();
                    if (!targetTrackAdded) {
                        targetTrack = mediaMuxer.addTrack(outputMediaFormat, targetTrack);
                        targetTrackAdded = true;
                    }
                    encodeFrameResult = RESULT_OUTPUT_MEDIA_FORMAT_CHANGED;
                    Log.d(TAG, "Encoder output format received " + outputMediaFormat);
                    break;
                default:
                    Log.e(TAG, "Unhandled value " + index + " when receiving encoded output frame");
                    break;
            }
        }

        return encodeFrameResult;
    }
}
