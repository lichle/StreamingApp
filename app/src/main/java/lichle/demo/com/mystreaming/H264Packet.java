package lichle.demo.com.mystreaming;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Created by lich on 9/5/18.
 */

public class H264Packet {

    //used on all packets
    private final static int MAX_PACKET_SIZE = Constants.MTU - 28;
    private RtpSocket mRtpSocket;
    private byte[] mBuffers;
    private long mTimeStamp;
    private RtspClient rtspClient;

    //contain mHeader from ByteBuffer (first 5 bytes)
    private byte[] mHeader = new byte[5];
    private byte[] mStapA;

    public H264Packet(RtspClient rtspClient) {
        this.rtspClient = rtspClient;
        mTimeStamp = new Random().nextInt();
        mRtpSocket = new RtpSocket();
        mRtpSocket.setSSRC(new Random().nextInt());
        try {
            mRtpSocket.setTimeToLive(64);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mRtpSocket.setClockFrequency(Constants.CLOCK_VIDEO_FREQUENCY);
    }

    public void close() {
        mRtpSocket.reset(false);
        mRtpSocket.close();
    }

    public void updateDestinationVideo() {
        mRtpSocket.setDestination(rtspClient.getHost(), rtspClient.getVideoPorts()[0], rtspClient.getVideoPorts()[1]);
    }

    public void createAndSendPacket(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        // We read a NAL units from ByteBuffer and we send them
        // NAL units are preceded with 0x00000001
        byteBuffer.rewind();
        byteBuffer.get(mHeader, 0, 5);
        mTimeStamp = bufferInfo.presentationTimeUs * 1000L;
        int naluLength = bufferInfo.size - byteBuffer.position() + 1;
        int type = mHeader[4] & 0x1F;

        if (type == 5) {
            mBuffers = mRtpSocket.requestBuffer();
            mRtpSocket.markNextPacket();
            mRtpSocket.updateTimestamp(mTimeStamp);
            System.arraycopy(mStapA, 0, mBuffers, Constants.RTP_HEADER_LENGTH, mStapA.length);
            mRtpSocket.commitBuffer(mStapA.length + Constants.RTP_HEADER_LENGTH);
        }
        // Small NAL unit => Single NAL unit
        if (naluLength <= MAX_PACKET_SIZE - Constants.RTP_HEADER_LENGTH - 2) {
            mBuffers = mRtpSocket.requestBuffer();
            mBuffers[Constants.RTP_HEADER_LENGTH] = mHeader[4];
            int cont = naluLength - 1;
            int length = cont < bufferInfo.size - byteBuffer.position() ? cont
                    : bufferInfo.size - byteBuffer.position();
            byteBuffer.get(mBuffers, Constants.RTP_HEADER_LENGTH + 1, length);
            mRtpSocket.updateTimestamp(mTimeStamp);
            mRtpSocket.markNextPacket();
            mRtpSocket.commitBuffer(naluLength + Constants.RTP_HEADER_LENGTH);
        }
        // Large NAL unit => Split nal unit
        else {
            // Set FU-A mHeader
            mHeader[1] = (byte) (mHeader[4] & 0x1F);  // FU mHeader type
            mHeader[1] += 0x80; // Start bit
            // Set FU-A indicator
            mHeader[0] = (byte) ((mHeader[4] & 0x60) & 0xFF); // FU indicator NRI
            mHeader[0] += 28;

            int sum = 1;
            while (sum < naluLength) {
                mBuffers = mRtpSocket.requestBuffer();
                mBuffers[Constants.RTP_HEADER_LENGTH] = mHeader[0];
                mBuffers[Constants.RTP_HEADER_LENGTH + 1] = mHeader[1];
                mRtpSocket.updateTimestamp(mTimeStamp);
                int cont = naluLength - sum > MAX_PACKET_SIZE - Constants.RTP_HEADER_LENGTH - 2 ?
                        MAX_PACKET_SIZE
                                - Constants.RTP_HEADER_LENGTH
                                - 2 : naluLength - sum;
                int length = cont < bufferInfo.size - byteBuffer.position() ? cont
                        : bufferInfo.size - byteBuffer.position();
                byteBuffer.get(mBuffers, Constants.RTP_HEADER_LENGTH + 2, length);
                sum += length;
                // Last packet before next NAL
                if (sum >= naluLength) {
                    // End bit on
                    mBuffers[Constants.RTP_HEADER_LENGTH + 1] += 0x40;
                    mRtpSocket.markNextPacket();
                }
                mRtpSocket.commitBuffer(length + Constants.RTP_HEADER_LENGTH + 2);
                // Switch start bit
                mHeader[1] = (byte) (mHeader[1] & 0x7F);
            }
        }
    }

    public void setSPSandPPS(byte[] sps, byte[] pps) {
        mStapA = new byte[sps.length + pps.length + 5];

        // STAP-A NAL mHeader is 24
        mStapA[0] = 24;

        // Write NALU 1 size into the array (NALU 1 is the SPS).
        mStapA[1] = (byte) (sps.length >> 8);
        mStapA[2] = (byte) (sps.length & 0xFF);

        // Write NALU 2 size into the array (NALU 2 is the PPS).
        mStapA[sps.length + 3] = (byte) (pps.length >> 8);
        mStapA[sps.length + 4] = (byte) (pps.length & 0xFF);

        // Write NALU 1 into the array, then write NALU 2 into the array.
        System.arraycopy(sps, 0, mStapA, 3, sps.length);
        System.arraycopy(pps, 0, mStapA, 5 + sps.length, pps.length);
    }

}
