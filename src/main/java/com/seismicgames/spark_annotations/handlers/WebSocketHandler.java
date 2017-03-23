package com.seismicgames.spark_annotations.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Created on 3/4/17.
 */
public class WebSocketHandler {
    public interface IWebSocket {
        @OnWebSocketConnect
        void onConnect(Session session);

        @OnWebSocketClose
        void onClose(Session session, int statusCode, String reason);

        @OnWebSocketMessage
        void onMessage(Session session, String message);

        void broadcastMessage(String message);
    }

    private static final Logger LOGGER = LogManager.getLogger(WebSocketHandler.class);

    private static WebSocketHandler instance;

    private List<IWebSocket> wsHandlers = new ArrayList<>();

    private WebSocketHandler() {}

    public static WebSocketHandler getInstance() {
        if(instance == null) {
            instance = new WebSocketHandler();
        }

        return instance;
    }

    public synchronized void addHandler(IWebSocket handler) {
        wsHandlers.add(handler);
    }

    public synchronized List<IWebSocket> getHandlers() {
        return wsHandlers;
    }

    public synchronized IWebSocket getHandler(Class<? extends IWebSocket> clazz) {
        for(IWebSocket handler : wsHandlers) {
            if (handler.getClass() == clazz) {
                return handler;
            }
        }

        return null;
    }
}
