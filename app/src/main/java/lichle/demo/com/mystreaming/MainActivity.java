package lichle.demo.com.mystreaming;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, SurfaceHolder.Callback {

    private Button mButton;

    private StreamEngine mStreamEngine;

    private String mUserName, mPassword;
    private int mWidth, mHeight, mFps, mBitrate;
    private String mServerUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        mButton = findViewById(R.id.toggle_start_stop_button);
        mButton.setOnClickListener(this);
        SurfaceView surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);

        mStreamEngine = new StreamEngine(surfaceView);

        configureStream();
    }

    private void configureStream() {
        mUserName = Constants.USER_NAME;
        mPassword = Constants.PASSWORD;
        mWidth = Constants.RESOLUTION_WIDTH;
        mHeight = Constants.RESOLUTION_HEIGHT;
        mFps = Constants.FPS;
        mBitrate = Constants.BIT_RATE;
        mServerUrl = Constants.SERVER_URL;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.toggle_start_stop_button) {
            if (!mStreamEngine.isStreaming()) {
                mButton.setText(R.string.pause);
                mStreamEngine.setAuthorization(mUserName, mPassword);
                boolean isSuccess = mStreamEngine.prepareVideo(mWidth, mHeight, mFps, mBitrate, 3);
                if (isSuccess) {
                    mStreamEngine.startStream(mServerUrl);
                }
            } else {
                mButton.setText(R.string.resume);
                mStreamEngine.stopStream();
            }
        }
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mStreamEngine.startPreview(mWidth, mHeight, mFps);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mStreamEngine.isStreaming()) {
            mStreamEngine.stopPreview();
            mStreamEngine.stopStream();
        }
    }

}
