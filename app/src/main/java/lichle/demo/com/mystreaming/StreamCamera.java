package lichle.demo.com.mystreaming;

import android.hardware.Camera;
import android.view.SurfaceView;

import java.util.List;

/**
 * Created by lich on 9/5/18.
 */

public class StreamCamera implements Camera.PreviewCallback {

    private SurfaceView mSurfaceView;
    private Camera mCamera;
    private ICameraData mOnRawData;
    private boolean mIsRunning, mIsPrepared;
    private byte[] mYUVBuffers;

    private int mWidth, mHeight, mFps, mImageFormat;


    public StreamCamera(SurfaceView surfaceView, ICameraData onRawData) {
        mSurfaceView = surfaceView;
        mOnRawData = onRawData;
    }

    public void setUpCamera(int width, int height, int fps) {
        this.mWidth = width;
        this.mHeight = height;
        this.mFps = fps;
        mIsPrepared = true;
        mImageFormat = Constants.CAMERA_DATA_FORMAT;
    }

    public void start() {
        mYUVBuffers = new byte[mWidth * mHeight * 3 / 2];
        if (mCamera == null && mIsPrepared) {
            try {
                mCamera = Camera.open();
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(mWidth, mHeight);
                parameters.setPreviewFormat(mImageFormat);
                int[] range = adaptFpsRange(mFps, parameters.getSupportedPreviewFpsRange());
                parameters.setPreviewFpsRange(range[0], range[1]);
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);

                mCamera.setParameters(parameters);
                mCamera.setPreviewDisplay(mSurfaceView.getHolder());
                mCamera.addCallbackBuffer(mYUVBuffers);
                mCamera.setPreviewCallbackWithBuffer(this);

                mCamera.startPreview();
                mIsRunning = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        if (mCamera != null && mIsRunning) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.setPreviewCallbackWithBuffer(null);
            mCamera.release();
            mCamera = null;
            mIsRunning = mIsPrepared = false;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mOnRawData.onNV21Data(data);
        camera.addCallbackBuffer(mYUVBuffers);
    }

    private int[] adaptFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }


}
