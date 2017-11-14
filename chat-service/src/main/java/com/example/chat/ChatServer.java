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
import com.auth0.jwt.algorithms.Algorithm;
import com.example.auth.AuthenticationServiceGrpc;
import com.example.auth.EnvVars;
import com.example.chat.grpc.ChatRoomServiceImpl;
import com.example.chat.grpc.ChatStreamServiceImpl;
import com.example.chat.grpc.JwtServerInterceptor;
import com.example.chat.repository.ChatRoomRepository;
import io.grpc.*;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.urlconnection.URLConnectionSender;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Created by rayt on 6/27/17.
 */
public class ChatServer {
  private static final Logger logger = Logger.getLogger(ChatServer.class.getName());

  public static void main(String[] args) throws IOException, InterruptedException {
    final AsyncReporter<Span> reporter = AsyncReporter.create(URLConnectionSender.create(EnvVars.ZIPKIN_URL));
    final GrpcTracing tracing = GrpcTracing.create(Tracing.newBuilder()
        .localServiceName("chat-service")
        .reporter(reporter)
        .build());

    final ChatRoomRepository repository = new ChatRoomRepository();
    final JwtServerInterceptor jwtServerInterceptor = new JwtServerInterceptor("auth-issuer", Algorithm.HMAC256("secret"));

    final ManagedChannel authChannel = ManagedChannelBuilder.forTarget(EnvVars.AUTH_SERVICE_URL)
        .intercept(tracing.newClientInterceptor())
        .usePlaintext(true)
        .build();

    final AuthenticationServiceGrpc.AuthenticationServiceBlockingStub authService = AuthenticationServiceGrpc.newBlockingStub(authChannel);
    final ChatRoomServiceImpl chatRoomService = new ChatRoomServiceImpl(repository, authService);
    final ChatStreamServiceImpl chatStreamService = new ChatStreamServiceImpl(repository);

    final Server server = ServerBuilder.forPort(EnvVars.CHAT_SERVICE_PORT)
        .addService(ServerInterceptors.intercept(chatRoomService, jwtServerInterceptor, tracing.newServerInterceptor()))
        .addService(ServerInterceptors.intercept(chatStreamService, jwtServerInterceptor, tracing.newServerInterceptor()))
        .build();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        server.shutdownNow();
        authChannel.shutdownNow();
      }
    });

    server.start();
    logger.info("Server Started on port " + EnvVars.CHAT_SERVICE_PORT);
    server.awaitTermination();
  }
}
