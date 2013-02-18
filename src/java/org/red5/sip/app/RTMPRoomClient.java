package org.red5.sip.app;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.openmeetings.persistence.beans.room.Client;
import org.red5.io.utils.ObjectMap;
import org.red5.server.api.IScope;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.service.IServiceInvoker;
import org.red5.server.net.rtmp.BaseRTMPClientHandler;
import org.red5.server.net.rtmp.Channel;
import org.red5.server.net.rtmp.ClientExceptionHandler;
import org.red5.server.net.rtmp.INetStreamEventHandler;
import org.red5.server.net.rtmp.RTMPClient;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.service.Call;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RTMPRoomClient extends RTMPClient implements INetStreamEventHandler, ClientExceptionHandler, IPendingServiceCallback, IMediaReceiver {

    private static final Logger log = LoggerFactory.getLogger(RTMPRoomClient.class);
    private static final int MAX_RETRY_NUMBER = 100;
    private static final int UPDATE_MS = 10000;

    private Set<Integer> broadcastIds = new HashSet<Integer>();
    private Map<Long,Integer> clientStreamMap = new HashMap<Long, Integer>();
    private String publicSID = null;
    private long broadCastId = -1;
    private RTMPConnection conn;
    private IMediaSender sender;
    private IoBuffer buffer;
    private int kt = 0;
    private Integer publishStreamId = null;
    private boolean reconnect = true;
    private int retryNumber = 0;
    private boolean micMuted = false;
    private boolean silence = true;
    private String sipNumber = null;
    private ISipNumberListener sipNumberListener = null;
    private long lastSendActivityMS = 0L;
    private Thread updateThread = new Thread(new Runnable() {
        public void run() {
            while(true) {
                try {
                    Thread.sleep(UPDATE_MS);
                    updateSipTransport();
                } catch (InterruptedException e) {
                    log.debug("updateThread was interrupted", e);
                    return;
                }
            }
        }
    });

    protected enum ServiceMethod {
        connect,
        listRoomBroadcast,
        getBroadCastId,
        getPublicSID,
        createStream,
        setUserAVSettings,
        setSipTransport,
        updateSipTransport,
        sendMessage,
        getSipNumber
    }

    final private int roomId;
    final private String context;
    final private String host;

    public RTMPRoomClient(String host, String context, int roomId) {
        super();
        this.roomId = roomId;
        this.context = context;
        this.host = host;
        this.setServiceProvider(this);
        this.setExceptionHandler(this);
        Field serviceInvoker = null;
        try {
            serviceInvoker = BaseRTMPClientHandler.class.getDeclaredField("serviceInvoker");
            serviceInvoker.setAccessible(true);
            serviceInvoker.set(this, new IServiceInvoker() {
                public boolean invoke(IServiceCall call, IScope iScope) {
                    call.setStatus(Call.STATUS_SUCCESS_VOID);
                    return true;
                }
                public boolean invoke(IServiceCall call, Object o) {
                    call.setStatus(Call.STATUS_SUCCESS_VOID);
                    return true;
                }
            });
        } catch (NoSuchFieldException e) {
        	log.error("NoSuchFieldException", e);
        } catch (IllegalAccessException e) {
        	log.error("NoSuchFieldException", e);
        }
    }

    public void start() {
        log.debug( "Connecting. Host: {}, Port: {}, Context: {}, RoomID: {}", new String[]{host, "1935", context, ""+roomId} );
        stop();
        reconnect = true;
        connect(host, 1935, context + "/" + roomId, this);
    }

    public void setSipNumberListener(ISipNumberListener sipNumberListener) {
        this.sipNumberListener = sipNumberListener;
    }

    public void init() {
        getPublicSID();
    }

    public void stop() {
        reconnect = false;
        if(conn != null) {
            disconnect();
        }
    }

    public void setSender(IMediaSender sender) {
        this.sender = sender;
    }

    protected void getPublicSID() {
        invoke("getPublicSID", this);
    }

    protected void getBroadCastId() {
        invoke("getBroadCastId", this);
    }

    protected void listBroadcastIds() {
        invoke("listRoomBroadcast", this);
    }

    private void createPlayStream( long broadCastId ) {

        log.debug( "create play stream" );
        broadcastIds.add((int)broadCastId);
        IPendingServiceCallback wrapper = new CreatePlayStreamCallBack(broadCastId);
        invoke( "createStream", null, wrapper );
    }

    private class CreatePlayStreamCallBack implements IPendingServiceCallback {

        private long broadCastId;

        public CreatePlayStreamCallBack(long broadCastId) {
            this.broadCastId = broadCastId;
        }

        public void resultReceived(IPendingServiceCall call) {

            Integer streamIdInt = (Integer) call.getResult();

            if (conn != null && streamIdInt != null && (publishStreamId == null || streamIdInt.intValue() != publishStreamId)) {
                clientStreamMap.put(broadCastId, streamIdInt);
                PlayNetStream stream = new PlayNetStream(sender);
                stream.setConnection(conn);
                stream.setStreamId(streamIdInt.intValue());
                conn.addClientStream(stream);
                play(streamIdInt, "" + broadCastId, -2000, -1000);
                stream.start();
            }
        }

    }

    protected void setSipTransport() {
        conn.invoke("setSipTransport", new Object[]{Long.valueOf(roomId), publicSID, ""+broadCastId}, this);
    }

    protected void setUserAVSettings() {
        String[] remoteMessage = new String[3];
        remoteMessage[0] = "avsettings";
        remoteMessage[1] = "0";
        remoteMessage[2] = "av";
        conn.invoke("setUserAVSettings", new Object[]{"av", remoteMessage, 120, 90}, this);
    }

    protected void getSipNumber() {
        conn.invoke("getSipNumber", new Object[]{Integer.valueOf(roomId).longValue()}, this);
    }

    protected void startStreaming() {
        //red5 -> SIP
        for(long broadCastId: broadcastIds) {
            if(broadCastId != this.broadCastId) {
                createPlayStream(broadCastId);
            }
        }
    }

    protected void updateSipTransport() {
        conn.invoke("updateSipTransport", this);
    }

    @Override
    public void connectionOpened( RTMPConnection conn, RTMP state ) {
        log.debug("RTMP Connection opened");
        super.connectionOpened( conn, state );
        this.conn = conn;
        retryNumber = 0;
    }

    @Override
    public void connectionClosed( RTMPConnection conn, RTMP state ) {
        log.debug( "RTMP Connection closed" );
        super.connectionClosed( conn, state );
        if(reconnect && ++retryNumber < MAX_RETRY_NUMBER) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                log.error("Reconnection pause was interrupted", e);
            }
            log.debug( "Try reconnect..." );
            this.start();
        } else {
            if(updateThread.isAlive()) {
                updateThread.interrupt();
            }
        }
    }


    @Override
	protected void onInvoke(RTMPConnection conn, Channel channel,
			Header source, Notify invoke, RTMP rtmp) {
		super.onInvoke(conn, channel, source, invoke, rtmp);

        if (invoke.getType() == IEvent.Type.STREAM_DATA) {
			return;
		}
        try {
            String methodName = invoke.getCall().getServiceMethodName();
            InvokeMethods method;
            try {
                method = InvokeMethods.valueOf(methodName);
            } catch (IllegalArgumentException e) {
                return;
            }
            switch(method) {
                case receiveExclusiveAudioFlag:
                    receiveExclusiveAudioFlag(Client.class.cast(invoke.getCall().getArguments()[0]));
                    break;
                case sendVarsToMessageWithClient:
                    sendVarsToMessageWithClient(invoke.getCall().getArguments()[0]);
                    break;
                case newStream:
                    newStream(Client.class.cast(invoke.getCall().getArguments()[0]));
                    break;
                case closeStream:
                    closeStream(Client.class.cast(invoke.getCall().getArguments()[0]));
                    break;
                default:
                    log.debug("Method not found: " + method + ", args number: " + invoke.getCall().getArguments().length);
            }
        } catch (ClassCastException e) {
            log.error("onInvoke error", e);
        }
	}

	@Override
    public void handleException(Throwable throwable) {
        log.error("Exception was: {}", throwable.getStackTrace());
        if(throwable instanceof RuntimeIoException) {
            if(reconnect && ++retryNumber < MAX_RETRY_NUMBER) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    log.error("Reconnection pause was interrupted", e);
                }
                this.start();
            } else {
                if(updateThread.isAlive()) {
                    updateThread.interrupt();
                }
            }
        }

    }

    /******************************************************************************************************************/
    /** Serive provider methods */
    /******************************************************************************************************************/

    enum InvokeMethods {
        receiveExclusiveAudioFlag,
        sendVarsToMessageWithClient,
        closeStream,
        newStream
    }

    public void receiveExclusiveAudioFlag(Client client) {
        log.debug("receiveExclusiveAudioFlag:" + client.getPublicSID());
        this.micMuted = !client.getPublicSID().equals(this.publicSID);
        log.info("Mic switched: " + this.micMuted);
    }

    public void sendVarsToMessageWithClient(Object message) {
        if(message instanceof Map) {
            try {
                Map map = Map.class.cast(message);
                Map msgValue = Map.class.cast(map.get("message"));
                if("kick".equals(map.get(0)) || "kick".equals(msgValue.get(0))) {
                    log.info("Kicked by moderator. Reconnect");
                    this.conn.close();
                } else if("updateMuteStatus".equals(msgValue.get(0))) {
                    Client client = (Client)msgValue.get(1);
                    log.info("Mic switched: " + client.getMicMuted());
                    this.micMuted = client.getMicMuted();
                }
            } catch (Exception ignored) {}
        }
        log.debug("sendVarsToMessageWithClient:" + message.toString());
    }

    public void closeStream(Client client) {
        log.debug("closeStream:" + client.getBroadCastID());
        Integer streamId = clientStreamMap.get(client.getBroadCastID());
        if(streamId != null) {
            clientStreamMap.remove(client.getBroadCastID());
            conn.getStreamById(streamId).stop();
            conn.removeClientStream(streamId);
            conn.deleteStreamById(streamId);
        }
    }

    public void newStream(Client client) {
        log.debug("newStream:" + client.getBroadCastID());
        if(broadcastIds.contains((int)client.getBroadCastID())) {
            closeStream(client);
        }
        createPlayStream(client.getBroadCastID());
    }

    /******************************************************************************************************************/

    public void resultReceived(IPendingServiceCall call) {
        log.trace( "service call result: " + call );
        ServiceMethod method;
        try {
            method = ServiceMethod.valueOf(call.getServiceMethodName());
        } catch (IllegalArgumentException e) {
            log.error("Unknown service method: " + call.getServiceMethodName());
            return;
        }
        switch (method) {
            case connect:
                log.info("connect");
                this.getSipNumber();
                break;
            case listRoomBroadcast:
                log.info("listRoomBroadcast");
                if(call.getResult() instanceof Collection)
                this.broadcastIds.addAll((Collection<Integer>)call.getResult());
                this.startStreaming();
                break;
            case getBroadCastId:
                log.info("getBroadCastId");
                this.broadCastId = ((Number)call.getResult()).intValue();
                this.setUserAVSettings();
                break;
            case getPublicSID:
                log.info("getPublicSID");
                this.publicSID = (String)call.getResult();
                this.getBroadCastId();
                this.listBroadcastIds();
                break;
            case createStream:
                log.info("createStream");
                publishStreamId = (Integer) call.getResult();
                publish(publishStreamId, ""+broadCastId, "live", this);
                this.setSipTransport();
                break;
            case setUserAVSettings:
                log.info("setUserAVSettings");
                //SIP -> red5
                createStream(this);
                break;
            case setSipTransport:
                log.info("setSipTransport");
                updateThread.start();
                break;
            case updateSipTransport:
                log.debug("updateSipTransport");
                break;
            case getSipNumber:
                log.info("getSipNumber");
                if(call.getResult() instanceof String) {
                    sipNumber = (String) call.getResult();
                    if(sipNumberListener != null) {
                        sipNumberListener.onSipNumber(sipNumber);
                    }
                } else {
                    log.error("getSipNumber invalid result: " + call.getResult());
                }
                break;
			default:
				break;
        }
    }

    public void soundActivity() {
        Object[] message = new Object[] {"audioActivity", !silence, this.publicSID};
	  	conn.invoke("sendMessage", message, this);
    }

    public void onStatus(Object obj)  {
        log.debug("onStatus: " + obj.toString());
    }

    public void onStreamEvent(Notify notify) {
        log.debug( "onStreamEvent " + notify );

        ObjectMap map = (ObjectMap) notify.getCall().getArguments()[ 0 ];
        String code = (String) map.get( "code" );

        if ( StatusCodes.NS_PUBLISH_START.equals( code ) ) {
            log.debug( "onStreamEvent Publish start" );
        }
    }


    public void pushAudio(byte[] audio, long ts, int codec ) throws IOException {

        if(micMuted) {
            return;
        }

        boolean silence = true;
        for (byte anAudio : audio) {
            if (anAudio != -1 && anAudio != -2 && anAudio != 126) {
                silence = false;
                break;
            }
        }
        if(silence != this.silence && lastSendActivityMS + 500 < System.currentTimeMillis()) {
            lastSendActivityMS = System.currentTimeMillis();
            this.silence = silence;
            soundActivity();
        }

        if(silence) {
            log.debug("Silence...");
            return;
        }

        if( publishStreamId == null) {
            return;
        }
        if ( buffer == null ) {
            buffer = IoBuffer.allocate( 1024 );
            buffer.setAutoExpand( true );
        }

        buffer.clear();

        buffer.put( (byte) codec ); // first byte 2 mono 5500; 6 mono 11025; 22
        // mono 11025 adpcm 82 nellymoser 8000 178
        // speex 8000
        buffer.put( audio );

        buffer.flip();

        AudioData audioData = new AudioData( buffer );
        audioData.setTimestamp( (int) ts );

        kt++;
        if ( kt < 10 ) {
            log.debug( "+++ " + audioData );
        }


        RTMPMessage rtmpMsg = new RTMPMessage();
        rtmpMsg.setBody( audioData );
        publishStreamData( publishStreamId, rtmpMsg );
    }
}
