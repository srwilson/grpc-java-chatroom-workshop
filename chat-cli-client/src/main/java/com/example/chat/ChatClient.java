/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.chat;

import brave.Tracing;
import brave.grpc.GrpcTracing;
import com.example.auth.*;
import com.example.chat.grpc.JwtCallCredential;
import com.example.chat.grpc.JwtClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jline.console.ConsoleReader;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.urlconnection.URLConnectionSender;

import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.example.chat.ConsoleUtil.printLine;

enum ClientStatus {
  STARTED, AUTHENTICATED, IN_ROOM
}

/**
 * Created by rayt on 6/27/17.
 */
public class ChatClient {
  private static Logger logger = Logger.getLogger(ChatClient.class.getName());

  private final ConsoleReader console = new ConsoleReader();

  // Initialize Tracing contexts
  private final AsyncReporter<Span> reporter = AsyncReporter.create(URLConnectionSender.create(EnvVars.ZIPKIN_URL));
  private final GrpcTracing tracing = GrpcTracing.create(Tracing.newBuilder()
          .localServiceName("chat-client")
          .reporter(reporter)
          .build());

  // Channels
  private ManagedChannel authChannel;
  private AuthenticationServiceGrpc.AuthenticationServiceBlockingStub authService;
  private ManagedChannel chatChannel;
  private ChatRoomServiceGrpc.ChatRoomServiceBlockingStub chatRoomService;
  private ChatStreamServiceGrpc.ChatStreamServiceStub chatStreamService;

  private CurrentState state = new CurrentState();

  // StreamObserver to send to the server
  private StreamObserver<ChatMessage> toServer;

  public ChatClient() throws IOException {
  }

  public static void main(String[] args) throws Exception {
    ChatClient client = new ChatClient();
    client.init();
  }

  public void init() throws Exception {
    initAuthService();
    prompt();
  }

  /**
   * Initialize a managed channel to connect to the auth service.
   * Set the authChannel and authService
   */
  public void initAuthService() {
    logger.info("initializing auth service");
    authChannel = ManagedChannelBuilder.forTarget("localhost:9091")
            .intercept(new JwtClientInterceptor())
            .intercept(tracing.newClientInterceptor())
            .usePlaintext(true).build();

    authService = AuthenticationServiceGrpc.newBlockingStub(authChannel);
  }

  /**
   * Initialize
   *
   * @param token
   */
  public void initChatServices(String token) {
    logger.info("initializing chat services with token: " + token);
    JwtCallCredential callCredential = new JwtCallCredential(token);

    chatChannel = ManagedChannelBuilder.forTarget("localhost:9092")
            .intercept(tracing.newClientInterceptor())
            .usePlaintext(true)
            .build();

    chatRoomService = ChatRoomServiceGrpc.newBlockingStub(chatChannel).withCallCredentials(callCredential);
    chatStreamService = ChatStreamServiceGrpc.newStub(chatChannel).withCallCredentials(callCredential);
  }

  public void initChatStream() {
    this.toServer = chatStreamService.chat(new StreamObserver<ChatMessageFromServer>() {
      @Override
      public void onNext(ChatMessageFromServer chatMessageFromServer) {
        if (!(chatMessageFromServer.getFrom().equalsIgnoreCase(state.username))) {
          String chatMessage = String.format("\n%tr %s:%s-> %s", chatMessageFromServer.getTimestamp().getSeconds(),
                  chatMessageFromServer.getRoomName(),
                  chatMessageFromServer.getFrom(),
                  chatMessageFromServer.getMessage());

          try {
            printLine(console, chatMessage);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }

      @Override
      public void onError(Throwable throwable) {
        logger.log(Level.SEVERE, "gRPC error", throwable);
      }

      @Override
      public void onCompleted() {
        logger.severe("server closed connection, shutting down...");
        shutdown();
      }
    });
  }

  protected void prompt() throws Exception {
/*
    //StringsCompleter stringsCompleter = new StringsCompleter("/login", "/quit", "/exit", "/join", "/leave", "/create", "/list");
    LineReader lineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(stringsCompleter)
        .build();
*/

    console.println("Press Ctrl+D or Ctrl+C to quit");

    while (true) {
      try {
        switch (state.status) {
          case STARTED:
            readLogin();
            break;
          case AUTHENTICATED:
          case IN_ROOM:
            readCommand();
            break;
        }
      } catch (Exception e) {
        e.printStackTrace();
        shutdown();
      }
    }
  }

  protected void shutdown() {
    logger.info("Exiting chat client");
    if (chatChannel != null) {
      chatChannel.shutdownNow();
    }
    if (authChannel != null) {
      authChannel.shutdownNow();
    }
    System.exit(1);
  }

  protected void readLogin() throws Exception {
    String prompt = "/login [username] | /quit\n-> ";

    String line = console.readLine(prompt);
    String[] splitLine = line.split(" ");
    String command = splitLine[0];
    if (splitLine.length >= 2) {
      String username = splitLine[1];
      if (command.equalsIgnoreCase("/create")) {
        logger.info("creating user not implemented");
        //createUser(username, consoleReader, authService);
      } else if (command.equalsIgnoreCase("/login")) {
        logger.info("processing login user");
        String password = console.readLine("password> ", '*');
        String token = authenticate(username, password);
        if (token != null) {
          this.state = new CurrentState(ClientStatus.AUTHENTICATED, username, token, null);
          initChatServices(token);
          initChatStream();
        }
      }
    } else if (command.equalsIgnoreCase("/quit")) {
      shutdown();
    }
  }

  protected void readCommand() throws IOException {
    String help = "[chat message] | /join [room] | /leave [room] | /create [room] | /list | /quit";
    String prompt = this.state.username + "-> ";

    String line = console.readLine(prompt);
    if (line.startsWith("/")) {
      if ("/quit".equalsIgnoreCase(line)) {
        shutdown();
      } else if ("/list".equalsIgnoreCase(line)) {
        listRooms();
      } else if ("/leave".equalsIgnoreCase(line)) {
        if (this.state.status != ClientStatus.IN_ROOM) {
          logger.severe("error - not in a room");
        } else {
          leaveRoom(state.room);
          this.state = new CurrentState(ClientStatus.AUTHENTICATED, state.username, state.token, null);
        }
      } else if ("/?".equalsIgnoreCase(line)) {
        console.println(help);
      } else {
        String[] splitLine = line.split(" ");
        if (splitLine.length == 2) {
          String command = splitLine[0];
          String room = splitLine[1];
          if ("/join".equalsIgnoreCase(command)) {
            if (this.state.status == ClientStatus.IN_ROOM) {
              logger.info("already in room [" + room + "], leaving...");
              leaveRoom(room);
              this.state = new CurrentState(ClientStatus.AUTHENTICATED, state.username, state.token, null);
            }
            joinRoom(room);
            this.state = new CurrentState(ClientStatus.IN_ROOM, state.username, state.token, room);
          } else if ("/create".equalsIgnoreCase(command)) {
            createRoom(room);
          }
        }
      }
    } else if (!line.isEmpty()) {
      // if the line was not a chat command then send it as a message to the other rooms
      if (this.state.status != ClientStatus.IN_ROOM) {
        logger.severe("error - not in a room");
      } else {
        sendMessage(state.room, line);
      }
    }
  }

  /**
   * Authenticate the username/password with AuthenticationService
   *
   * @param username
   * @param password
   * @return If authenticated, return the authentication token, else, return null
   */
  private String authenticate(String username, String password) {

    logger.info("authenticating user: " + username);

    try {
      AuthenticationResponse authenticationReponse = authService.authenticate(AuthenticationRequest.newBuilder()
              .setUsername(username)
              .setPassword(password)
              .build());

      String token = authenticationReponse.getToken();

      AuthorizationResponse authorizationResponse = authService.authorization(AuthorizationRequest.newBuilder()
              .setToken(token)
              .build());

      logger.info("user has these roles: " + authorizationResponse.getRolesList());

      return token;
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
        logger.log(Level.SEVERE, "user not authenticated: " + username, e);
      } else {
        logger.log(Level.SEVERE, "caught a gRPC exception", e);
      }
      return null;
    }
  }

  /**
   * List all the chat rooms from the server
   */
  private void listRooms() {
    logger.info("listing rooms");
    Iterator<Room> rooms = chatRoomService.getRooms(Empty.getDefaultInstance());
    rooms.forEachRemaining(r -> {
      try {
        console.println("Room: " + r.getName());
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  /**
   * Leave the room
   */
  private void leaveRoom(String room) {
    logger.info("leaving room: " + room);
    toServer.onNext(ChatMessage.newBuilder()
            .setRoomName(room)
            .setType(MessageType.LEAVE)
            .build());
    logger.info("left room: " + room);
  }

  /**
   * Join a Room
   *
   * @param room
   */
  private void joinRoom(String room) {
    logger.info("joinining room: " + room);
    toServer.onNext(ChatMessage.newBuilder()
            .setRoomName(room)
            .setType(MessageType.JOIN)
            .build());
    logger.info("joined room: " + room);
  }

  /**
   * Create Room
   *
   * @param room
   */
  private void createRoom(String room) {
    logger.info("create room: " + room);
    chatRoomService.createRoom(Room.newBuilder()
            .setName(room)
            .build());
    logger.info("created room: " + room);
  }

  /**
   * Send a message
   *
   * @param room
   * @param message
   */
  private void sendMessage(String room, String message) {
    logger.info("sending chat message");
    toServer.onNext(ChatMessage.newBuilder()
            .setRoomName(room)
            .setType(MessageType.TEXT)
            .setMessage(message)
            .build());
  }

  class CurrentState {
    final ClientStatus status;
    final String username;
    final String token;
    final String room;

    CurrentState() {
      this.status = ClientStatus.STARTED;
      this.username = null;
      this.token = null;
      this.room = null;
    }

    CurrentState(ClientStatus status, String username, String token, String room) {
      this.status = status;
      this.username = username;
      this.token = token;
      this.room = room;
    }
  }
}
