package lichle.demo.com.mystreaming;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

/**
 * Created by lich on 9/5/18.
 */

public class RtcpSocket {

    public static final int MTU = 1500;
    public static final int PACKET_LENGTH = 28;
    private static final String TAG = "RtcpSocket";
    private byte[] mBuffer = new byte[MTU];
    private int mOctetCount = 0, mPacketCount = 0;
    private long mInterval, mDelta, mNow, mOld;

    private MulticastSocket mSocket;
    private DatagramPacket mDatagramPacket;

    public RtcpSocket() {
    /*							     Version(2)  Padding(0)					 					*/
    /*									 ^		  ^			PT = 0	    						*/
    /*									 |		  |				^								*/
    /*									 | --------			 	|								*/
    /*									 | |---------------------								*/
    /*									 | ||													*/
    /*									 | ||													*/
        mBuffer[0] = (byte) Integer.parseInt("10000000", 2);

    /* Packet Type PT */
        mBuffer[1] = (byte) 200;

    /* Byte 2,3          ->  Length		                     */
        setLong(PACKET_LENGTH / 4 - 1, 2, 4);

    /* Byte 4,5,6,7      ->  SSRC                            */
    /* Byte 8,9,10,11    ->  NTP timestamp hb				 */
    /* Byte 12,13,14,15  ->  NTP timestamp lb				 */
    /* Byte 16,17,18,19  ->  RTP timestamp		             */
    /* Byte 20,21,22,23  ->  packet count				 	 */
    /* Byte 24,25,26,27  ->  octet count			         */

        // By default we sent one report every 3 second
        mInterval = 3000;
        mDelta = mInterval;

        try {
            mSocket = new MulticastSocket();
        } catch (IOException e) {
            // Very unlikely to happen. Means that all UDP ports are already being used
            throw new RuntimeException(e.getMessage());
        }
        mDatagramPacket = new DatagramPacket(mBuffer, 1);
    }

    public void setSSRC(int ssrc) {
        setLong(ssrc, 4, 8);
        mPacketCount = 0;
        mOctetCount = 0;
        setLong(mPacketCount, 20, 24);
        setLong(mOctetCount, 24, 28);
    }

    /**
     * Updates the number of packets sent, and the total amount of data sent.
     *
     * @param length The length of the packet
     */
    private boolean updateSend(int length) {
        mPacketCount += 1;
        mOctetCount += length;
        setLong(mPacketCount, 20, 24);
        setLong(mOctetCount, 24, 28);

        mNow = System.currentTimeMillis();
        mDelta += mOld != 0 ? mNow - mOld : 0;
        mOld = mNow;
        if (mInterval > 0) {
            if (mDelta >= mInterval) {
                // We send a Sender Report
                mDelta = 0;
                return true;
            }
        }
        return false;
    }

    /**
     * Resets the reports (total number of bytes sent, number of packets sent, etc.)
     */
    public void reset() {
        mPacketCount = 0;
        mOctetCount = 0;
        setLong(mPacketCount, 20, 24);
        setLong(mOctetCount, 24, 28);
        mDelta = mNow = mOld = 0;
    }

    private void setLong(long n, int begin, int end) {
        for (end--; end >= begin; end--) {
            mBuffer[end] = (byte) (n % 256);
            n >>= 8;
        }
    }

    private void setData(long ntpts, long rtpts) {
        long hb = ntpts / 1000000000;
        long lb = ((ntpts - hb * 1000000000) * 4294967296L) / 1000000000;
        setLong(hb, 8, 12);
        setLong(lb, 12, 16);
        setLong(rtpts, 16, 20);
    }

    public void close() {
        mSocket.close();
    }

    public void setDestination(InetAddress dest, int dport) {
        mDatagramPacket.setPort(dport);
        mDatagramPacket.setAddress(dest);
    }

    /**
     * Updates the number of packets sent, and the total amount of data sent.
     *
     * @param length The length of the packet
     * @param rtpts  The RTP timestamp.
     * @param port   to send packet
     **/
    public void update(int length, long rtpts, int port) {
        if (updateSend(length)) {
            send(System.nanoTime(), rtpts, port);
        }
    }

    /**
     * Sends the RTCP packet over the network.
     *
     * @param ntpts the NTP timestamp.
     * @param rtpts the RTP timestamp.
     */
    private void send(final long ntpts, final long rtpts, final int port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                setData(ntpts, rtpts);
                mDatagramPacket.setLength(PACKET_LENGTH);
                mDatagramPacket.setPort(port);
                try {
                    mSocket.send(mDatagramPacket);
                    Log.i(TAG, "send report, " + mDatagramPacket.getPort() + " Port");
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "send UDP report error", e);
                }
            }
        }).start();
    }

}
