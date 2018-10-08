package lichle.demo.com.mystreaming;

import android.media.MediaCodec;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by lich on 9/5/18.
 */

public class RtspClient {

    private static final String TAG = "RtspClient";

    private final long mTimestamp;
    private final int mTrackID = 1;
    private String mHost = "";
    private int mPort;
    private String mPath;
    private int mCSeq = 0;
    private String mAuthorization = null;
    private String mUserName, mPassword;
    private String mSessionId;

    //For connect to Server
    private Thread mThread;
    private Socket mConnectionSocket;
    private OutputStream mOutputStream;
    private BufferedReader mReader;
    private BufferedWriter mWriter;

    private byte[] mSps, mPps;
    //for udp
    private int[] mVideoPorts = new int[]{5002, 5003};

    private volatile boolean mIsStreaming = false;

    private H264Packet mH264Packet;

    public RtspClient() {
        long uptime = System.currentTimeMillis();
        mTimestamp = (uptime / 1000) << 32 & (((uptime - ((uptime / 1000) * 1000)) >> 32) / 1000); // NTP mTimestamp
    }

    public static String createVideoBody(int trackVideo, String sps, String pps) {
        return "m=video " + (5000 + 2 * trackVideo)
                + " RTP/AVP " + Constants.PAYLOAD_TYPE + "\r\n"
                + "a=rtpmap:" + Constants.PAYLOAD_TYPE
                + " H264/" + Constants.CLOCK_VIDEO_FREQUENCY + "\r\n"
                + "a=fmtp:" + Constants.PAYLOAD_TYPE
                + " packetization-mode=1;sprop-parameter-sets=" + sps + "," + pps + ";\r\n"
                + "a=control:trackID=" + trackVideo + "\r\n";
    }

    public void setAuthorization(String user, String password) {
        this.mUserName = user;
        this.mPassword = password;
    }

    public boolean isStreaming() {
        return mIsStreaming;
    }

    public void setUrl(String url) {
        Matcher rtspMatcher = Constants.RTSP_URL_PARTTERN.matcher(url);
        rtspMatcher.matches();
        mHost = rtspMatcher.group(1);
        mPort = Integer.parseInt((rtspMatcher.group(2) != null) ? rtspMatcher.group(2) : "1935");
        mPath = "/" + rtspMatcher.group(3) + "/" + rtspMatcher.group(4);
    }

    public String getHost() {
        return mHost;
    }

    public void setH264Parameters(ByteBuffer sps, ByteBuffer pps) {
        byte[] mSPS = new byte[sps.capacity() - 4];
        sps.position(4);
        sps.get(mSPS, 0, mSPS.length);
        byte[] mPPS = new byte[pps.capacity() - 4];
        pps.position(4);
        pps.get(mPPS, 0, mPPS.length);
        this.mSps = mSPS;
        this.mPps = mPPS;
    }

    public void connect() {
        if (!mIsStreaming) {
            mH264Packet = new H264Packet(this);
            if (mSps != null && mPps != null) {
                mH264Packet.setSPSandPPS(mSps, mPps);
            }
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mConnectionSocket = new Socket();
                        SocketAddress socketAddress = new InetSocketAddress(mHost, mPort);
                        mConnectionSocket.connect(socketAddress, 5000);

                        mConnectionSocket.setSoTimeout(5000);
                        mReader = new BufferedReader(new InputStreamReader(mConnectionSocket.getInputStream()));
                        mOutputStream = mConnectionSocket.getOutputStream();
                        mWriter = new BufferedWriter(new OutputStreamWriter(mOutputStream));

                        //OPTIONS Command
                        mWriter.write(requestOptions());
                        mWriter.flush();
                        getResponse();

                        //ANNOUNCE command
                        mWriter.write(requestAnnounce());
                        mWriter.flush();
                        //check if you need credential for stream, if you need try connect with credential
                        String response = getResponse();
                        int status = getResponseStatus(response);
                        if (status == 403) {
                            Log.e(TAG, "Response 403: Access denied");
                            return;
                        } else if (status == 401) {
                            if (mUserName == null || mPassword == null) {
                                return;
                            } else {
                                mWriter.write(sendAnnounceWithAuth(response));
                                mWriter.flush();
                                int authStatus = getResponseStatus(getResponse());
                                if (authStatus == -1) {
                                    Log.e(TAG, "Response: Authentication error");
                                }
                            }
                        }

                        //SET UP command
                        mWriter.write(requestSetup(mTrackID));
                        mWriter.flush();
                        getResponse();
                        //update Server ports
                        mH264Packet.updateDestinationVideo();

                        //RECORD command
                        mWriter.write(requestRecord());
                        mWriter.flush();
                        getResponse();

                        mIsStreaming = true;
                    } catch (IOException | NullPointerException e) {
                        Log.e(TAG, "Connection error", e);
                        e.printStackTrace();
                        mIsStreaming = false;
                    }
                }
            });
            mThread.start();
        }
    }

    public void disconnect() {
        if (mIsStreaming) {
            mIsStreaming = false;
            if (mH264Packet != null) {
                mH264Packet.close();
            }
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mWriter.write(sendTearDown());
                        mConnectionSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Disconnect error", e);
                        e.printStackTrace();
                    }
                }
            });
            mThread.start();
            mCSeq = 0;
            mSps = null;
            mPps = null;
            mSessionId = null;
        }
    }

    private String requestAnnounce() {
        String body = createBody();
        String announce;
        if (mAuthorization == null) {
            announce = "ANNOUNCE rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                    + "CSeq: " + (++mCSeq) + "\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Content-Type: application/sdp\r\n\r\n"
                    + body;
        } else {
            announce = "ANNOUNCE rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                    + "CSeq: " + (++mCSeq) + "\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: " + mAuthorization + "\r\n"
                    + "Content-Type: application/sdp\r\n\r\n"
                    + body;
        }
        Log.i(TAG, announce);
        return announce;
    }

    private String createBody() {
        String sSPS = null;
        String sPPS = null;
        if (mSps != null && mPps != null) {
            sSPS = Base64.encodeToString(mSps, 0, mSps.length, Base64.NO_WRAP);
            sPPS = Base64.encodeToString(mPps, 0, mPps.length, Base64.NO_WRAP);
        }
        String body = createVideoBody(mTrackID, sSPS, sPPS);
        return "v=0\r\n"
                + "o=- " + mTimestamp + " " + mTimestamp
                + " IN IP4 " + "127.0.0.1" + "\r\n"
                + "s=Unnamed\r\n"
                + "i=N/A\r\n" + "c=IN IP4 "
                + mHost + "\r\n" +
                // means the session is permanent
                "t=0 0\r\n"
                + "a=recvonly\r\n"
                + body;
    }

    private String requestSetup(int track) {
        String params = "UDP;unicast;client_port=" + (5000 + 2 * track) + "-" + (5000 + 2 * track + 1) + ";mode=record";
        String setup = "SETUP rtsp://"
                + mHost + ":" + mPort + mPath
                + "/trackID=" + track
                + " RTSP/1.0\r\n"
                + "Transport: RTP/AVP/" + params + "\r\n"
                + addHeaders(mAuthorization);
        Log.i(TAG, setup);
        return setup;
    }

    private String requestOptions() {
        String options = "OPTIONS rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                + addHeaders(mAuthorization);
        Log.i(TAG, options);
        return options;
    }

    private String requestRecord() {
        String record = "RECORD rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                + "Range: npt=0.000-\r\n"
                + addHeaders(mAuthorization);
        Log.i(TAG, record);
        return record;
    }

    private String sendTearDown() {
        String teardown = "TEARDOWN rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                + addHeaders(mAuthorization);
        Log.i(TAG, teardown);
        return teardown;
    }

    private String addHeaders(String authorization) {
        return "CSeq: " + (++mCSeq) + "\r\n"
                + (mSessionId != null ? "Session: " + mSessionId + "\r\n" : "")
                // For some reason you may have to remove last "\r\n" in the next line to make the RTSP client work with your wowza server :/
                + (authorization != null ? "Authorization: " + authorization + "\r\n" : "") + "\r\n";
    }

    private String getResponse() {
        try {
            String response = "";
            String line;

            while ((line = mReader.readLine()) != null) {
                if (line.contains("Session")) {
                    Pattern rtspPattern = Pattern.compile("Session: (\\w+)");
                    Matcher matcher = rtspPattern.matcher(line);
                    if (matcher.find()) {
                        mSessionId = matcher.group(1);
                    }
                    mSessionId = line.split(";")[0].split(":")[1].trim();
                }
                if (line.contains("server_port")) {
                    Pattern rtspPattern = Pattern.compile("server_port=([0-9]+)-([0-9]+)");
                    Matcher matcher = rtspPattern.matcher(line);
                    if (matcher.find()) {
                        mVideoPorts[0] = Integer.parseInt(matcher.group(1));
                        mVideoPorts[1] = Integer.parseInt(matcher.group(2));
                    }
                }
                response += line + "\n";
                //end of response
                if (line.length() < 3) break;
            }
            Log.i(TAG, response);
            return response;
        } catch (IOException e) {
            Log.e(TAG, "read error", e);
            return null;
        }
    }

    private String sendAnnounceWithAuth(String authResponse) {
        mAuthorization = createAuth(authResponse);
        Log.i("Auth", mAuthorization);
        String body = createBody();
        String announce = "ANNOUNCE rtsp://" + mHost + ":" + mPort + mPath + " RTSP/1.0\r\n"
                + "CSeq: " + (++mCSeq) + "\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Authorization: " + mAuthorization + "\r\n"
                + "Content-Type: application/sdp\r\n\r\n"
                + body;
        Log.i(TAG, announce);
        return announce;
    }

    /**
     * Create basic authentication if required.
     *
     * @param authResponse
     * @return
     */
    private String createAuth(String authResponse) {
        Pattern authPattern =
                Pattern.compile("realm=\"(.+)\",\\s+nonce=\"(\\w+)\"", Pattern.CASE_INSENSITIVE);
        Matcher matcher = authPattern.matcher(authResponse);
        if (matcher.find()) {
            Log.i(TAG, "using digest auth");
            String realm = matcher.group(1);
            String nonce = matcher.group(2);
            String hash1 = Utils.getMd5Hash(mUserName + ":" + realm + ":" + mPassword);
            String hash2 = Utils.getMd5Hash("ANNOUNCE:rtsp://" + mHost + ":" + mPort + mPath);
            String hash3 = Utils.getMd5Hash(hash1 + ":" + nonce + ":" + hash2);
            return "Digest username=\"" + mUserName
                    + "\",realm=\"" + realm
                    + "\",nonce=\"" + nonce
                    + "\",uri=\"rtsp://" + mHost + ":" + mPort + mPath
                    + "\",response=\"" + hash3 + "\"";
        } else {
            Log.i(TAG, "using basic auth");
            String data = mUserName + ":" + mPassword;
            String base64Data = Base64.encodeToString(data.getBytes(), Base64.DEFAULT);
            return "Basic " + base64Data;
        }
    }

    private int getResponseStatus(String response) {
        Matcher matcher =
                Pattern.compile("RTSP/\\d.\\d (\\d+) (\\w+)", Pattern.CASE_INSENSITIVE).matcher(response);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        } else {
            return -1;
        }
    }

    public int[] getVideoPorts() {
        return mVideoPorts;
    }

    public void sendDataToServer(ByteBuffer h264Buffer, MediaCodec.BufferInfo info) {
        if (isStreaming()) {
            mH264Packet.createAndSendPacket(h264Buffer, info);
        }
    }

}
