import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class PaintServer {
    // Odalara göre istemciler ve çizim geçmişleri
    private static Map<String, List<SocketChannel>> roomClients = new HashMap<>();
    private static Map<String, List<String>> roomHistories = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        
        // Sunucu ağdaki tüm bağlantılara açık hale getirildi
        serverSocket.bind(new InetSocketAddress(8080)); 
        
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Sunucu 8080 portunda tüm ağa açık şekilde başlatıldı...");

        // Autosave - 30 Saniye
        new Timer(true).scheduleAtFixedRate(new TimerTask() {
            public void run() {
                for (String room : roomHistories.keySet()) {
                    try (PrintWriter out = new PrintWriter(new FileWriter("autosave_" + room + ".txt"))) {
                        for (String cmd : roomHistories.get(room)) out.println(cmd);
                    } catch (IOException e) {}
                }
            }
        }, 30000, 30000);

        loadAutosaves(); 

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) continue;

                if (key.isAcceptable()) {
                    SocketChannel client = serverSocket.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(4096);
                    int bytesRead;
                    try {
                        bytesRead = client.read(buffer);
                    } catch (IOException e) {
                        client.close();
                        continue;
                    }

                    if (bytesRead == -1) {
                        client.close();
                        continue;
                    }

                    String msg = new String(buffer.array(), 0, bytesRead).trim();
                    String[] commands = msg.split("\n");

                    for (String cmd : commands) {
                        cmd = cmd.trim();
                        if (cmd.isEmpty()) continue;
                        processCommand(cmd, client);
                    }
                }
            }
        }
    }

    private static void processCommand(String msg, SocketChannel client) throws IOException {
        String[] parts = msg.split("\\|");
        String type = parts[0];

        if (type.equals("ODALARI_GETIR")) {
            StringBuilder sb = new StringBuilder("ODALAR");
            for (String room : roomHistories.keySet()) {
                sb.append("|").append(room);
            }
            sb.append("\n");
            client.write(ByteBuffer.wrap(sb.toString().getBytes()));
        }
        else if (type.equals("BAGLAN")) {
            if (parts.length >= 3) {
                String roomName = parts[2];
                roomClients.putIfAbsent(roomName, new ArrayList<>());
                roomHistories.putIfAbsent(roomName, new ArrayList<>());
                
                roomClients.get(roomName).add(client);
                System.out.println("Yeni Bağlantı -> Kullanıcı: " + parts[1] + " | Oda: " + roomName);

                for (String historyCmd : roomHistories.get(roomName)) {
                    client.write(ByteBuffer.wrap((historyCmd + "\n").getBytes()));
                }
            }
        }
        else if (type.equals("CIZ") || type.equals("KUTU")) {
            String targetRoom = null;
            for (Map.Entry<String, List<SocketChannel>> entry : roomClients.entrySet()) {
                if (entry.getValue().contains(client)) {
                    targetRoom = entry.getKey();
                    break;
                }
            }

            if (targetRoom != null) {
                roomHistories.get(targetRoom).add(msg);
                byte[] data = (msg + "\n").getBytes();
                for (SocketChannel c : roomClients.get(targetRoom)) {
                    if (c != client && c.isOpen()) {
                        c.write(ByteBuffer.wrap(data));
                    }
                }
            }
        }
    }

    private static void loadAutosaves() {
        File dir = new File(".");
        File[] files = dir.listFiles((d, name) -> name.startsWith("autosave_") && name.endsWith(".txt"));
        if (files != null) {
            for (File f : files) {
                String roomName = f.getName().substring(9, f.getName().length() - 4);
                roomHistories.putIfAbsent(roomName, new ArrayList<>());
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        roomHistories.get(roomName).add(line);
                    }
                } catch (IOException e) {}
            }
        }
    }
}