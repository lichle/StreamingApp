package lichle.demo.com.mystreaming;

import android.graphics.ImageFormat;
import android.media.MediaCodecInfo;

import java.util.regex.Pattern;

/**
 * Created by lich on 9/5/18.
 */

public class Constants {

    public static final int RAW_DATA_QUEUE_SIZE = 20;
    public static final String USER_NAME = "lich";
    public static final String PASSWORD = "12345678";
    public static final String SERVER_URL = "rtsp://192.168.100.8:1935/live/myStream";
    public static final int RESOLUTION_WIDTH = 640;
    public static final int RESOLUTION_HEIGHT = 480;
    public static final int FPS = 30;
    public static final int BIT_RATE = 2000000;
    public static final int PAYLOAD_TYPE = 96;
    public static final int MC_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    public static final long CLOCK_VIDEO_FREQUENCY = 90000L;
    public static final int RTP_HEADER_LENGTH = 12;
    public static final String MIME_TYPE = "video/avc";
    public static final int MTU = 1300;
    public static final Pattern RTSP_URL_PARTTERN = Pattern.compile("^rtsps?://([^/:]+)(?::(\\d+))*/([^/]+)/?([^*]*)$");
    public static final int CAMERA_DATA_FORMAT = ImageFormat.NV21;

}
