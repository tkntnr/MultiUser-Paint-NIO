import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PaintClient extends JFrame {

    // multi cihaz denemelerinde "localhost" yerine IP yaz.
    private static final String SUNUCU_IP = "localhost";

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private JPanel canvas;
    private int lastX, lastY;

    // THREAD-SAFE
    private List<String> localHistory = new CopyOnWriteArrayList<>();
    private Rectangle selectionRect = null;
    private Point selectionStart = null;
    private List<String> clipboard = new ArrayList<>();

    private Color currentColor = Color.BLACK;
    private int penSize = 2;
    private String userName;
    private String roomName;

    private JPopupMenu popupMenu;
    private int popupX, popupY;
    private JLabel lblStatus;

    public PaintClient() {
        userName = JOptionPane.showInputDialog(null, "Kullanıcı Adınız:", "Giriş", JOptionPane.QUESTION_MESSAGE);
        if (userName == null || userName.trim().isEmpty())
            userName = "Kullanici" + (System.currentTimeMillis() % 100);

        List<String> activeRooms = new ArrayList<>();
        try {
            Socket tempSocket = new Socket();
            tempSocket.connect(new InetSocketAddress(SUNUCU_IP, 8080), 2000); // 2 saniye timeout (UI donmasını önler)
            PrintWriter tempOut = new PrintWriter(tempSocket.getOutputStream(), true);
            BufferedReader tempIn = new BufferedReader(new InputStreamReader(tempSocket.getInputStream()));

            tempOut.println("ODALARI_GETIR");
            String response = tempIn.readLine();
            if (response != null && response.startsWith("ODALAR")) {
                String[] parts = response.split("\\|");
                for (int i = 1; i < parts.length; i++) {
                    activeRooms.add(parts[i]);
                }
            }
            tempSocket.close();
        } catch (Exception e) {
            System.out.println("Sunucu kapalı, odalar çekilemedi (Zaman aşımı veya Bağlantı reddedildi).");
        }

        activeRooms.add(0, "-- Yeni Dosya (Oda) Oluştur --");
        String[] options = activeRooms.toArray(new String[0]);

        String selected = (String) JOptionPane.showInputDialog(null,
                "Lütfen katılmak istediğiniz dosyayı seçin:",
                "Açık Dosyalar Listesi",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (selected == null)
            System.exit(0);

        if (selected.equals(options[0])) {
            roomName = JOptionPane.showInputDialog(null, "Yeni Dosya Adı:", "Dosya Oluştur",
                    JOptionPane.QUESTION_MESSAGE);
            if (roomName == null || roomName.trim().isEmpty())
                roomName = "GenelDosya";
        } else {
            roomName = selected;
        }

        setTitle("MultiUserPaint | Kullanıcı: " + userName + " | Dosya: " + roomName);
        setSize(950, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        popupMenu = new JPopupMenu();
        JMenuItem itemCopy = new JMenuItem("📄 Kopyala");
        JMenuItem itemCut = new JMenuItem("✂️ Kes");
        JMenuItem itemPaste = new JMenuItem("📋 Buraya Yapıştır");

        itemCopy.addActionListener(e -> {
            copySelectedLines();
            showToast("✅ Kopyalandı!");
        });
        itemCut.addActionListener(e -> {
            cutSelectedLines();
            showToast("✂️ Kesildi!");
        });
        itemPaste.addActionListener(e -> {
            pasteAtLocation(popupX, popupY);
            showToast("📋 Yapıştırıldı!");
        });

        popupMenu.add(itemCopy);
        popupMenu.add(itemCut);
        popupMenu.addSeparator();
        popupMenu.add(itemPaste);

        JToolBar toolBar = new JToolBar();
        JButton btnColor = new JButton("🎨 Renk Seç");
        btnColor.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnColor.setForeground(currentColor);

        btnColor.addActionListener(e -> {
            Color selectedC = JColorChooser.showDialog(this, "Kalem Rengi Seç", currentColor);
            if (selectedC != null) {
                currentColor = selectedC;
                btnColor.setForeground(currentColor);
            }
        });

        JLabel lblSize = new JLabel(" Kalınlık: 2px ");
        JSlider sliderSize = new JSlider(1, 20, 2);
        sliderSize.setMaximumSize(new Dimension(120, 40));
        sliderSize.addChangeListener(e -> {
            penSize = sliderSize.getValue();
            lblSize.setText(" Kalınlık: " + penSize + "px ");
        });

        lblStatus = new JLabel(" Durum: Hazır ");
        lblStatus.setForeground(Color.GRAY);
        lblStatus.setFont(new Font("SansSerif", Font.BOLD, 12));

        JLabel lblInfo = new JLabel(" | ℹ️ Sol Tık: Çiz | Sağ Tık + Sürükle: Seç | Sağ Tık: Menü | ");
        lblInfo.setForeground(Color.GRAY);

        toolBar.add(btnColor);
        toolBar.addSeparator();
        toolBar.add(lblSize);
        toolBar.add(sliderSize);
        toolBar.addSeparator();
        toolBar.add(lblStatus);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(lblInfo);
        add(toolBar, BorderLayout.NORTH);

        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                for (String lineData : localHistory) {
                    try { // BOZUK VERİ KALKANI
                        String[] parts = lineData.split("\\|");
                        if (parts[0].equals("CIZ") && parts.length >= 7) {
                            g2d.setColor(new Color(Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                                    Integer.parseInt(parts[7])));
                            int size = parts.length >= 9 ? Integer.parseInt(parts[8]) : 2;
                            g2d.setStroke(new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g2d.drawLine(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                        } else if (parts[0].equals("KUTU") && parts.length >= 5) {
                            g2d.setColor(Color.WHITE);
                            g2d.fillRect(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                        }
                    } catch (Exception ex) {
                        // Bozuk satırı atla
                    }
                }

                if (selectionRect != null && selectionRect.width > 0 && selectionRect.height > 0) {
                    g2d.setColor(Color.BLUE);
                    g2d.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                            new float[] { 5f, 5f }, 0f));
                    g2d.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
                }
            }
        };
        canvas.setBackground(Color.WHITE);

        InputMap im = canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = canvas.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "Copy");
        am.put("Copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                copySelectedLines();
                showToast("Kopyalandı!");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "Cut");
        am.put("Cut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                cutSelectedLines();
                showToast("Kesildi!");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "Paste");
        am.put("Paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                pasteAtLocation(canvas.getWidth() / 2, canvas.getHeight() / 2);
                showToast("Yapıştırıldı!");
            }
        });

        canvas.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    selectionStart = e.getPoint();
                    selectionRect = new Rectangle(selectionStart);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    selectionRect = null;
                    canvas.repaint();
                    lastX = e.getX();
                    lastY = e.getY();
                }
            }

            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    popupX = e.getX();
                    popupY = e.getY();
                    itemCopy.setEnabled(selectionRect != null && selectionRect.width > 0);
                    itemCut.setEnabled(selectionRect != null && selectionRect.width > 0);
                    popupMenu.show(canvas, popupX, popupY);
                    canvas.repaint();
                }
            }
        });

        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int x = Math.min(selectionStart.x, e.getX());
                    int y = Math.min(selectionStart.y, e.getY());
                    int width = Math.abs(e.getX() - selectionStart.x);
                    int height = Math.abs(e.getY() - selectionStart.y);
                    selectionRect = new Rectangle(x, y, width, height);
                    canvas.repaint();
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    int x = e.getX();
                    int y = e.getY();
                    String cmd = "CIZ|" + lastX + "|" + lastY + "|" + x + "|" + y + "|" +
                            currentColor.getRed() + "|" + currentColor.getGreen() + "|" + currentColor.getBlue() + "|"
                            + penSize;
                    localHistory.add(cmd);
                    canvas.repaint();
                    if (out != null)
                        out.println(cmd);
                    lastX = x;
                    lastY = y;
                }
            }
        });

        add(canvas, BorderLayout.CENTER);
        setVisible(true);
        connectToServer();
    }

    private void showToast(String message) {
        lblStatus.setText(" " + message + " ");
        lblStatus.setForeground(new Color(0, 150, 0));
        Timer timer = new Timer(2000, evt -> {
            lblStatus.setText(" Durum: Hazır ");
            lblStatus.setForeground(Color.GRAY);
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void performCopy() {
        if (selectionRect == null || selectionRect.width == 0)
            return;
        clipboard.clear();
        for (String lineData : localHistory) {
            String[] parts = lineData.split("\\|");
            if (parts[0].equals("CIZ") && parts.length >= 7) {
                int startX = Integer.parseInt(parts[1]);
                int startY = Integer.parseInt(parts[2]);
                if (selectionRect.contains(startX, startY))
                    clipboard.add(lineData);
            }
        }
    }

    private void copySelectedLines() {
        performCopy();
        selectionRect = null;
        canvas.repaint();
    }

    private void cutSelectedLines() {
        performCopy();
        String cmd = "KUTU|" + selectionRect.x + "|" + selectionRect.y + "|" + selectionRect.width + "|"
                + selectionRect.height;
        localHistory.add(cmd);
        if (out != null)
            out.println(cmd);
        selectionRect = null;
        canvas.repaint();
    }

    private void pasteAtLocation(int destX, int destY) {
        if (clipboard.isEmpty()) {
            showToast("Hafıza Boş!");
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;

        for (String lineData : clipboard) {
            String[] parts = lineData.split("\\|");
            if (parts[0].equals("CIZ") && parts.length >= 7) {
                minX = Math.min(minX, Math.min(Integer.parseInt(parts[1]), Integer.parseInt(parts[3])));
                minY = Math.min(minY, Math.min(Integer.parseInt(parts[2]), Integer.parseInt(parts[4])));
            }
        }

        int offsetX = destX - minX;
        int offsetY = destY - minY;

        for (String lineData : clipboard) {
            String[] parts = lineData.split("\\|");
            if (parts[0].equals("CIZ") && parts.length >= 7) {
                int startX = Integer.parseInt(parts[1]) + offsetX;
                int startY = Integer.parseInt(parts[2]) + offsetY;
                int endX = Integer.parseInt(parts[3]) + offsetX;
                int endY = Integer.parseInt(parts[4]) + offsetY;
                int size = parts.length >= 9 ? Integer.parseInt(parts[8]) : 2;

                String cmd = "CIZ|" + startX + "|" + startY + "|" + endX + "|" + endY + "|" +
                        parts[5] + "|" + parts[6] + "|" + parts[7] + "|" + size;
                localHistory.add(cmd);
                if (out != null)
                    out.println(cmd);
            }
        }
        selectionRect = null;
        canvas.repaint();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(SUNUCU_IP, 8080), 3000); // 3 saniye timeout
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                out.println("BAGLAN|" + userName + "|" + roomName);

                String message;
                while ((message = in.readLine()) != null) {
                    String[] parts = message.split("\\|");
                    if (parts[0].equals("CIZ") || parts[0].equals("KUTU")) {
                        localHistory.add(message);
                        canvas.repaint();
                    }
                }
            } catch (IOException e) {
                System.out.println("Bağlantı kurulamadı veya koptu.");
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null,
                            "Sunucuya bağlanılamadı! Lütfen sunucu IP adresini ve internet bağlantınızı kontrol edin.\nUygulama çevrimdışı modda açılacaktır.",
                            "Bağlantı Hatası",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PaintClient());
    }
}