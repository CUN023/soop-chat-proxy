package com.example.proxy;

import com.github.getcurrentthread.soopapi.SOOPClient;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SOOP(구 아프리카TV) 채팅을 읽기 전용으로 받아와서,
 * 우리 퀴즈 웹페이지(브라우저)가 접속할 수 있는 WebSocket으로 그대로 릴레이하는 프록시.
 *
 * 접속 예: wss://<배포된 주소>/?bid=스트리머아이디
 *
 * 프론트엔드(브라우저)는 SOOP에 직접 붙지 않고, 이 프록시에만 붙습니다.
 * 실제 SOOP 접속/인증/프로토콜 파싱은 soopapi 라이브러리가 대신 처리해줍니다.
 */
public class ChatProxyServer extends WebSocketServer {

    // 방송인당 하나의 SOOP 연결만 유지 (여러 시청자가 같은 방송을 봐도 SOOP에는 1번만 접속)
    private final SOOPClient soopClient = new SOOPClient();

    // bid(스트리머 아이디) -> 그 방송 채팅을 구독 중인 우리 웹페이지 접속자들
    private final Map<String, Set<WebSocket>> subscribers = new ConcurrentHashMap<>();

    private final Gson gson = new Gson();

    public ChatProxyServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onStart() {
        System.out.println("[프록시] 시작됨. 포트: " + getPort());

        // 전역 리스너: 어떤 bid에서 채팅이 오든 여기로 다 들어옴
        soopClient.on(ChatEvent.CHAT_MESSAGE, (String bid, ChatMessageEvent e) -> {
            Set<WebSocket> conns = subscribers.get(bid);
            if (conns == null || conns.isEmpty()) return;

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "chat");
            payload.put("bid", bid);
            payload.put("nickname", e.senderNickname());
            payload.put("message", e.message());
            String json = gson.toJson(payload);

            for (WebSocket ws : conns) {
                if (ws.isOpen()) {
                    ws.send(json);
                }
            }
        });

        // 연결 끊김 상태도 프론트엔드에 알려주면 디버깅에 도움됨 (선택 사항)
        soopClient.on(ChatEvent.DISCONNECTED, (bid, e) -> {
            broadcastStatus(bid, "disconnected");
        });
        soopClient.on(ChatEvent.RECONNECTED, (bid, e) -> {
            broadcastStatus(bid, "reconnected");
        });
    }

    private void broadcastStatus(String bid, String status) {
        Set<WebSocket> conns = subscribers.get(bid);
        if (conns == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "status");
        payload.put("bid", bid);
        payload.put("status", status);
        String json = gson.toJson(payload);
        for (WebSocket ws : conns) {
            if (ws.isOpen()) ws.send(json);
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String bid = getQueryParam(conn.getResourceDescriptor(), "bid");

        if (bid == null || bid.isBlank()) {
            conn.send(gson.toJson(Map.of(
                "type", "error",
                "message", "bid 쿼리 파라미터가 필요합니다. 예: wss://주소/?bid=streamerId"
            )));
            conn.close();
            return;
        }

        subscribers.computeIfAbsent(bid, k -> new CopyOnWriteArraySet<>()).add(conn);

        // 이미 이 bid에 연결돼 있으면 아무 일도 안 일어남(dedup). 없으면 즉시 비동기 연결 시작 (읽기 전용, 인증 불필요)
        soopClient.add(bid);

        conn.send(gson.toJson(Map.of("type", "connected", "bid", bid)));
        System.out.println("[+] 접속: bid=" + bid + " / 현재 구독자 수: " + subscribers.get(bid).size());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        subscribers.values().forEach(set -> set.remove(conn));
        System.out.println("[-] 접속 종료 (code=" + code + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // 읽기 전용 프록시이므로 브라우저에서 보내는 메시지는 무시합니다.
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[오류] " + ex.getMessage());
    }

    private String getQueryParam(String resourceDescriptor, String key) {
        try {
            URI uri = new URI(resourceDescriptor);
            String query = uri.getQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void main(String[] args) {
        // Render/Railway 같은 호스팅은 PORT 환경변수로 포트를 지정해줍니다.
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        ChatProxyServer server = new ChatProxyServer(port);
        server.setReuseAddr(true);
        server.start();
    }
}
