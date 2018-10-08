package lichle.demo.com.mystreaming;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Created by lich on 9/5/18.
 */

public class VideoEncoder {

    private static final String TAG = "VideoEncoder";
    private static final Object sLock = new Object();

    private MediaCodec mMediaCodec;
    private Thread mThread;
    private IH264Data mH264DataListener;
    private MediaCodec.BufferInfo mVideoInfo = new MediaCodec.BufferInfo();
    private long mPresentTimeUs;
    private boolean mIsRunning, mIsH264ParametersSet;
    private BlockingDeque<byte[]> mRawQueue;

    private int mVideoEncoderFormat;

    public VideoEncoder(IH264Data onH264Data) {
        mH264DataListener = onH264Data;
        mRawQueue = new LinkedBlockingDeque<>(Constants.RAW_DATA_QUEUE_SIZE);
    }

    public boolean setUpMediaCodecEncoder(int width, int height, int fps, int bitRate, int iFrameInterval) {
        MediaCodecInfo encoder = chooseVideoEncoder(Constants.MIME_TYPE, Constants.MC_COLOR_FORMAT);
        try {
            if (encoder != null) {
                mMediaCodec = MediaCodec.createByCodecName(encoder.getName());
            } else {
                Log.e(TAG, "The media codec can not be found ");
                return false;
            }
            MediaFormat videoFormat;
            videoFormat = MediaFormat.createVideoFormat(Constants.MIME_TYPE, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, this.mVideoEncoderFormat);
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
            mMediaCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mIsRunning = false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void reset() {
        synchronized (sLock) {
            mIsRunning = false;
            if (mThread != null) {
                mThread.interrupt();
                try {
                    mThread.join(2000);
                } catch (InterruptedException e) {
                    mThread.interrupt();
                }
                mThread = null;
            }
            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            }
            mRawQueue.clear();
            mIsH264ParametersSet = false;
        }
    }

    public void putDataToNV21Queue(byte[] data) {
        synchronized (sLock) {
            if (mIsRunning) {
                try {
                    mRawQueue.add(data);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    public void start(boolean resetTs) {
        synchronized (sLock) {
            if (mMediaCodec != null) {
                mIsH264ParametersSet = false;
                if (resetTs) {
                    mPresentTimeUs = System.nanoTime() / 1000;
                }
                mMediaCodec.start();
                //mBuffers to mBuffers
                mThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!Thread.interrupted()) {
                            try {
                                byte[] buffer = mRawQueue.take();
                                getH264DataFromMediaCodec(buffer);
                            } catch (InterruptedException e) {
                                if (mThread != null) {
                                    mThread.interrupt();
                                }
                            }
                        }
                    }
                });
                mThread.start();
                mIsRunning = true;
            } else {
                Log.e(TAG, "VideoEncoder has not been initialized yet");
            }
        }
    }

    private void getH264DataFromMediaCodec(byte[] buffer) {
        int inIndex = mMediaCodec.dequeueInputBuffer(-1);
        if (inIndex >= 0) {
            ByteBuffer inBuffer = mMediaCodec.getInputBuffer(inIndex);
            if (null != inBuffer) {
                inBuffer.put(buffer, 0, buffer.length);
                long pts = System.nanoTime() / 1000 - mPresentTimeUs;
                mMediaCodec.queueInputBuffer(inIndex, 0, buffer.length, pts, 0);
            }
        }
        for (; ; ) {
            int outIndex = mMediaCodec.dequeueOutputBuffer(mVideoInfo, 0);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat mediaFormat = mMediaCodec.getOutputFormat();
                mH264DataListener.onParametersDecoded(mediaFormat.getByteBuffer("csd-0"), mediaFormat.getByteBuffer("csd-1"));
                mIsH264ParametersSet = true;
            } else if (outIndex >= 0) {
                //This ByteBuffer is H264
                ByteBuffer outBuffer = mMediaCodec.getOutputBuffer(outIndex);
                if ((mVideoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (outBuffer != null && !mIsH264ParametersSet) {
                        Pair<ByteBuffer, ByteBuffer> buffers =
                                decodeH264ParametersFromBuffer(outBuffer.duplicate(), mVideoInfo.size);
                        if (buffers != null) {
                            mH264DataListener.onParametersDecoded(buffers.first, buffers.second);
                            mIsH264ParametersSet = true;
                        }
                    }
                }
                mVideoInfo.presentationTimeUs = System.nanoTime() / 1000 - mPresentTimeUs;
                mH264DataListener.onH264Data(outBuffer, mVideoInfo);
                mMediaCodec.releaseOutputBuffer(outIndex, false);
            } else {
                break;
            }
        }
    }

    /**
     * Get H264 parameters (sps, pps) in case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED is not called.
     */
    private Pair<ByteBuffer, ByteBuffer> decodeH264ParametersFromBuffer(ByteBuffer outputBuffer, int length) {
        byte[] mSPS = null, mPPS = null;
        byte[] csd = new byte[length];
        outputBuffer.get(csd, 0, length);
        int i = 0;
        int spsIndex = -1;
        int ppsIndex = -1;
        while (i < length - 4) {
            if (csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 0 && csd[i + 3] == 1) {
                if (spsIndex == -1) {
                    spsIndex = i;
                } else {
                    ppsIndex = i;
                    break;
                }
            }
            i++;
        }
        if (spsIndex != -1 && ppsIndex != -1) {
            mSPS = new byte[ppsIndex];
            System.arraycopy(csd, spsIndex, mSPS, 0, ppsIndex);
            mPPS = new byte[length - ppsIndex];
            System.arraycopy(csd, ppsIndex, mPPS, 0, length - ppsIndex);
        }
        if (mSPS != null && mPPS != null) {
            return new Pair<>(ByteBuffer.wrap(mSPS), ByteBuffer.wrap(mPPS));
        }
        return null;
    }


    /**
     * Retrieve Media Codec Info that supports mine type: "video/adv" and color: "MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar"
     *
     * @param mime
     * @return
     */
    private MediaCodecInfo chooseVideoEncoder(String mime, int mc_color) {
        List<MediaCodecInfo> mediaCodecInfoList = getSupportedEncoders(mime);
        for (MediaCodecInfo mci : mediaCodecInfoList) {
            MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
            for (int color : codecCapabilities.colorFormats) {
                if (color == mc_color) {
                    this.mVideoEncoderFormat = Constants.MC_COLOR_FORMAT;
                    return mci;
                }
            }
        }
        return null;
    }

    /**
     * Get all MediaCodecInfo by mime type
     *
     * @param mime
     * @return
     */
    private List<MediaCodecInfo> getSupportedEncoders(String mime) {
        List<MediaCodecInfo> mediaCodecInfoList = new ArrayList<>();
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] mediaCodecInfos = mediaCodecList.getCodecInfos();
        for (MediaCodecInfo mci : mediaCodecInfos) {
            if (!mci.isEncoder()) {
                continue;
            }
            String[] types = mci.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mime)) {
                    mediaCodecInfoList.add(mci);
                }
            }
        }
        return mediaCodecInfoList;
    }


}
