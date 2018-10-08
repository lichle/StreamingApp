package lichle.demo.com.mystreaming;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/**
 * Created by lich on 9/5/18.
 */

public interface IH264Data {

    void onParametersDecoded(ByteBuffer sps, ByteBuffer pps);

    void onH264Data(ByteBuffer buffer, MediaCodec.BufferInfo info);

}
