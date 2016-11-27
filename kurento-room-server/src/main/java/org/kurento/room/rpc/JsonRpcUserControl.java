/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kurento.room.rpc;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;

import org.kurento.client.Composite;
import org.kurento.client.ErrorEvent;
import org.kurento.client.EventListener;
import org.kurento.client.HubPort;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.PassThrough;
import org.kurento.client.RecorderEndpoint;
import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.kurento.room.NotificationRoomManager;
import org.kurento.room.api.pojo.ParticipantRequest;
import org.kurento.room.api.pojo.UserParticipant;
import org.kurento.room.exception.RoomException;
import org.kurento.room.internal.ProtocolElements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.gson.JsonObject;


/**
 * Controls the user interactions by delegating her JSON-RPC requests to the room API.
 *
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 */
public class JsonRpcUserControl {

  private static final Logger log = LoggerFactory.getLogger(JsonRpcUserControl.class);

  protected NotificationRoomManager roomManager;
  
  // recording variables
  private Composite composite;
  private HubPort hubPort;
  private RecorderEndpoint recorderEndpoint;

private PassThrough passThru;

  @Autowired
  public JsonRpcUserControl(NotificationRoomManager roomManager) {
    this.roomManager = roomManager;
  }

  public void joinRoom(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) throws IOException, InterruptedException,
      ExecutionException {
    String roomName = getStringParam(request, ProtocolElements.JOINROOM_ROOM_PARAM);
    String userName = getStringParam(request, ProtocolElements.JOINROOM_USER_PARAM);

    boolean dataChannels = false;
    if (request.getParams().has(ProtocolElements.JOINROOM_DATACHANNELS_PARAM)) {
      dataChannels = request.getParams().get(ProtocolElements.JOINROOM_DATACHANNELS_PARAM)
          .getAsBoolean();
    }

    ParticipantSession participantSession = getParticipantSession(transaction);
    participantSession.setParticipantName(userName);
    participantSession.setRoomName(roomName);
    participantSession.setDataChannels(dataChannels);

    roomManager.joinRoom(userName, roomName, dataChannels, true, participantRequest);
  }


  public void publishVideo(Transaction transaction, Request<JsonObject> request, ParticipantRequest participantRequest) {
    String sdpOffer = getStringParam(request, ProtocolElements.PUBLISHVIDEO_SDPOFFER_PARAM);
    boolean doLoopback = getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_DOLOOPBACK_PARAM);
  
    // Recording
  	String participantId = participantRequest.getParticipantId();
    MediaPipeline pipeline = roomManager.getPipeline(participantId);

    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-S");
    String now = df.format(new Date());
    String fileName = now + "_" + participantId + ".webm";
    String webMFilePath = "file://home/kristina/recordings/" + fileName;
    log.info("RECORDER - INITIALIZING");
    
    boolean isRoomRecording =roomManager.IsRoomRecording(participantId); 
    
    if (isRoomRecording == false){
	    this.composite = new Composite.Builder(pipeline).build();
	    this.hubPort = new HubPort.Builder(composite).build();
	
	    this.passThru= new PassThrough.Builder(pipeline).build();
	    this.hubPort.connect(passThru);
	
	    log.info("RECORDER - BUILDING");
	    
	    // creating recorder
	    this.recorderEndpoint = new RecorderEndpoint.Builder(pipeline, webMFilePath)
	            .withMediaProfile(MediaProfileSpecType.WEBM)
	            .build();
		this.recorderEndpoint.addErrorListener(new EventListener<ErrorEvent>() {
		    @Override
		    public void onEvent(ErrorEvent event) {
		      String desc =
		          event.getType() + ": " + event.getDescription() + "(errCode=" + event.getErrorCode()
		              + ")";
		      log.warn("RECORDING error encountered: {}",  desc);
		    }
		  });
		this.passThru.connect(this.recorderEndpoint);
    }
	//	Recording - end
	
    log.info("RECORDER - PUBLISHING MEDIA");
    roomManager.publishMedia(participantRequest, sdpOffer, doLoopback, this.hubPort);
    // it does for each MediaElement:    participant.getPublisher().apply(mediaElem);
    
    if (isRoomRecording == false){
    	log.info("RECORDER - STARTED RECORDING for participant {}", participantId);	
    	this.recorderEndpoint.record();
    }
  }
  
  public void unpublishVideo(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    roomManager.unpublishMedia(participantRequest);
  }

  public void receiveVideoFrom(final Transaction transaction, final Request<JsonObject> request,
      ParticipantRequest participantRequest) {

    String senderName = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SENDER_PARAM);
    senderName = senderName.substring(0, senderName.indexOf("_"));

    String sdpOffer = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SDPOFFER_PARAM);

    roomManager.subscribe(senderName, sdpOffer, participantRequest);
  }

  public void unsubscribeFromVideo(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {

    String senderName = getStringParam(request, ProtocolElements.UNSUBSCRIBEFROMVIDEO_SENDER_PARAM);
    senderName = senderName.substring(0, senderName.indexOf("_"));

    roomManager.unsubscribe(senderName, participantRequest);
  }

  public void leaveRoomAfterConnClosed(String sessionId) {
    try {
      roomManager.evictParticipant(sessionId);
      log.info("Evicted participant with sessionId {}", sessionId);
    } catch (RoomException e) {
      log.warn("Unable to evict: {}", e.getMessage());
      log.trace("Unable to evict user", e);
    }
  }

  public void leaveRoom(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    boolean exists = false;
    String pid = participantRequest.getParticipantId();
    // trying with room info from session
    String roomName = null;
    if (transaction != null) {
      roomName = getParticipantSession(transaction).getRoomName();
    }
    if (roomName == null) { // null when afterConnectionClosed
      log.warn("No room information found for participant with session Id {}. "
          + "Using the admin method to evict the user.", pid);
      leaveRoomAfterConnClosed(pid);
    } else {
      // sanity check, don't call leaveRoom unless the id checks out
      for (UserParticipant part : roomManager.getParticipants(roomName)) {
        if (part.getParticipantId().equals(participantRequest.getParticipantId())) {
          exists = true;
          break;
        }
      }
      if (exists) {
        log.debug("Participant with sessionId {} is leaving room {}", pid, roomName);
        roomManager.leaveRoom(participantRequest);
        log.info("Participant with sessionId {} has left room {}", pid, roomName);
      } else {
        log.warn("Participant with session Id {} not found in room {}. "
            + "Using the admin method to evict the user.", pid, roomName);
        leaveRoomAfterConnClosed(pid);
      }
    }
  }

  public void onIceCandidate(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
    String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
    String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
    int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);

    roomManager.onIceCandidate(endpointName, candidate, sdpMLineIndex, sdpMid, participantRequest);
  }

  public void sendMessage(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    String userName = getStringParam(request, ProtocolElements.SENDMESSAGE_USER_PARAM);
    String roomName = getStringParam(request, ProtocolElements.SENDMESSAGE_ROOM_PARAM);
    String message = getStringParam(request, ProtocolElements.SENDMESSAGE_MESSAGE_PARAM);

    log.debug("Message from {} in room {}: '{}'", userName, roomName, message);

    roomManager.sendMessage(message, userName, roomName, participantRequest);
  }

  public void customRequest(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    throw new RuntimeException("Unsupported method");
  }

  public ParticipantSession getParticipantSession(Transaction transaction) {
    Session session = transaction.getSession();
    ParticipantSession participantSession = (ParticipantSession) session.getAttributes().get(
        ParticipantSession.SESSION_KEY);
    if (participantSession == null) {
      participantSession = new ParticipantSession();
      session.getAttributes().put(ParticipantSession.SESSION_KEY, participantSession);
    }
    return participantSession;
  }

  public static String getStringParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    return request.getParams().get(key).getAsString();
  }

  public static int getIntParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    return request.getParams().get(key).getAsInt();
  }

  public static boolean getBooleanParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    return request.getParams().get(key).getAsBoolean();
  }
}
