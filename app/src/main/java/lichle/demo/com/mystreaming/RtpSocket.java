package lichle.demo.com.mystreaming;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by lich on 9/5/18.
 */

public class RtpSocket implements Runnable {

    private static final String TAG = "RtpSocket";

    private byte[][] mBuffers;
    private long[] mTimestamps;
    private Semaphore mBufferRequested, mBufferCommitted;
    private Thread mThread;
    private int mBufferOut;
    private long mClock = 0;
    private int mSeq = 0;
    private int mBufferCount, mBufferIn;
    private boolean mIsRunning;

    //For sending RTP reports
    private RtcpSocket mSenderReport;

    private MulticastSocket mSocket;
    private DatagramPacket[] mPackets;
    private int mPort = -1;

    /**
     * This RTP mRtpSocket implements a buffering mechanism relying on a FIFO of mBuffers and a Thread.
     */
    public RtpSocket() {
        mIsRunning = true;
        mBufferCount = 300;
        mBuffers = new byte[mBufferCount][];
        resetFifo();

        for (int i = 0; i < mBufferCount; i++) {
            mBuffers[i] = new byte[Constants.MTU];
      /*							     Version(2)  Padding(0)					 					*/
      /*									 ^		  ^			Extension(0)						*/
      /*									 |		  |				^								*/
      /*									 | --------				|								*/
      /*									 | |---------------------								*/
      /*									 | ||  -----------------------> Source Identifier(0)	*/
      /*									 | ||  |												*/
            mBuffers[i][0] = (byte) Integer.parseInt("10000000", 2);
            mBuffers[i][1] = (byte) Constants.PAYLOAD_TYPE;

      /* Byte 2,3        ->  Sequence Number                   */
      /* Byte 4,5,6,7    ->  Timestamp                         */
      /* Byte 8,9,10,11  ->  Sync Source Identifier            */
        }

        mSenderReport = new RtcpSocket();
        mSenderReport.reset();
        mPackets = new DatagramPacket[mBufferCount];
        for (int i = 0; i < mBufferCount; i++) {
            mPackets[i] = new DatagramPacket(mBuffers[i], 1);
        }
        try {
            mSocket = new MulticastSocket();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        mSocket.close();
        mSenderReport.close();
    }

    protected void resetFifo() {
        mBufferIn = 0;
        mBufferOut = 0;
        mTimestamps = new long[mBufferCount];
        mBufferRequested = new Semaphore(mBufferCount);
        mBufferCommitted = new Semaphore(0);
    }

    public void reset(boolean running) {
        this.mIsRunning = running;
        mBufferCommitted.drainPermits();
        mBufferRequested.drainPermits();
        resetFifo();
    }

    /**
     * Sets the SSRC of the stream.
     */
    public void setSSRC(int ssrc) {
        setLongSSRC(ssrc);
        mSenderReport.setSSRC(ssrc);
    }

    /**
     * Sets the Time To Live of the UDP mPackets.
     */
    public void setTimeToLive(int ttl) throws IOException {
        mSocket.setTimeToLive(ttl);
    }

    /**
     * Sets the destination address and to which the mPackets will be sent.
     */
    public void setDestination(String dest, int dport, int rtcpPort) {
        try {
            if (dport != 0 && rtcpPort != 0) {
                mPort = dport;
                for (int i = 0; i < mBufferCount; i++) {
                    mPackets[i].setPort(dport);
                    mPackets[i].setAddress(InetAddress.getByName(dest));
                }
                mSenderReport.setDestination(InetAddress.getByName(dest), rtcpPort);
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    protected void setLongSSRC(int ssrc) {
        for (int i = 0; i < mBufferCount; i++) {
            setLong(mBuffers[i], ssrc, 8, 12);
        }
    }

    /**
     * Sets the mClock frequency of the stream in Hz.
     */
    public void setClockFrequency(long clock) {
        this.mClock = clock;
    }

    /**
     * Returns an available mBuffers from the FIFO, it can then be modified.
     **/
    public byte[] requestBuffer() {
        try {
            mBufferRequested.acquire();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            try {
                Thread.currentThread().join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mBuffers[mBufferIn][1] &= 0x7F;
        return mBuffers[mBufferIn];
    }

    /**
     * Overwrites the timestamp in the packet.
     *
     * @param timestamp The new timestamp in ns.
     **/
    public void updateTimestamp(long timestamp) {
        long ts = timestamp * mClock / 1000000000L;
        mTimestamps[mBufferIn] = ts;
        setLong(mBuffers[mBufferIn], ts, 4, 8);
    }

    /**
     * Sends the RTP packet over the network.
     */
    public void commitBuffer(int length) {
        //Increments the sequence number.
        setLong(mBuffers[mBufferIn], ++mSeq, 2, 4);
        implementCommitBuffer(length);
        if (++mBufferIn >= mBufferCount) {
            mBufferIn = 0;
        }
        mBufferCommitted.release();
        if (mThread == null) {
            mThread = new Thread(this);
            mThread.start();
        }
    }

    protected void implementCommitBuffer(int length) {
        mPackets[mBufferIn].setLength(length);
    }

    /**
     * Sets the marker in the RTP packet.
     */
    public void markNextPacket() {
        mBuffers[mBufferIn][1] |= 0x80;
    }

    protected void setLong(byte[] buffer, long n, int begin, int end) {
        for (end--; end >= begin; end--) {
            buffer[end] = (byte) (n % 256);
            n >>= 8;
        }
    }

    /**
     * The Thread sends the mPackets in the FIFO one by one at a constant rate.
     */
    @Override
    public void run() {
        try {
            while (mBufferCommitted.tryAcquire(4, TimeUnit.SECONDS)) {
                if (mIsRunning) {
                    mSenderReport.update(mPackets[mBufferOut].getLength(), mTimestamps[mBufferOut], mPort);
                    mSocket.send(mPackets[mBufferOut]);
                    Log.i(TAG, "send packet, " + mPackets[mBufferOut].getLength() + " Size, " + mPackets[mBufferOut].getPort() + " Port");
                    if (++mBufferOut >= mBufferCount) {
                        mBufferOut = 0;
                    }
                    mBufferRequested.release();
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        mThread = null;
        resetFifo();
        mSenderReport.reset();
    }

}
