package lichle.demo.com.mystreaming;

import android.media.MediaCodec;
import android.view.SurfaceView;

import java.nio.ByteBuffer;

/**
 * Created by lich on 9/5/18.
 */

public class StreamEngine implements IH264Data, ICameraData {

    private StreamCamera mStreamCamera;
    private VideoEncoder mVideoEncoder;
    private boolean mIsStreaming = false;
    private boolean mIsPreviewed;

    private RtspClient mRtspClient;

    public StreamEngine(SurfaceView surfaceView) {
        mStreamCamera = new StreamCamera(surfaceView, this);
        mRtspClient = new RtspClient();
        mVideoEncoder = new VideoEncoder(this);
    }

    public boolean prepareVideo(int width, int height, int fps, int bitrate, int iFrameInterval) {
        if (mIsPreviewed) {
            stopPreview();
            mIsPreviewed = false;
        }
        mStreamCamera.setUpCamera(width, height, fps);
        return mVideoEncoder.setUpMediaCodecEncoder(width, height, fps, bitrate, iFrameInterval);
    }

    public boolean isStreaming() {
        return mIsStreaming;
    }

    public void startPreview(int width, int height, int fps) {
        mStreamCamera.setUpCamera(width, height, fps);
        mStreamCamera.start();
        mIsPreviewed = true;
    }

    public void stopPreview() {
        if (mIsPreviewed) {
            mStreamCamera.stop();
            mIsPreviewed = false;
        }
    }

    public void startStream(String url) {
        if (!mIsStreaming) {
            mRtspClient.setUrl(url);

            mStreamCamera.start();
            mVideoEncoder.start(true);

            mIsStreaming = true;
            mIsPreviewed = true;
        }
    }

    public void stopStream() {
        if (mIsStreaming) {
            mRtspClient.disconnect();
            mIsStreaming = false;
            mVideoEncoder.reset();
        }
    }

    public void setAuthorization(String user, String password) {
        mRtspClient.setAuthorization(user, password);
    }

    @Override
    public void onParametersDecoded(ByteBuffer sps, ByteBuffer pps) {
        ByteBuffer newSps = sps.duplicate();
        ByteBuffer newPps = pps.duplicate();
        mRtspClient.setH264Parameters(newSps, newPps);
        mRtspClient.connect();
    }

    @Override
    public void onH264Data(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        mRtspClient.sendDataToServer(buffer, info);
    }

    @Override
    public void onNV21Data(byte[] data) {
        mVideoEncoder.putDataToNV21Queue(data);
    }


}
