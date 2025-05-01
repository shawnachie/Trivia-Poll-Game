import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;

/**
 * Listens for UDP messages from clients and adds them to a queue
 */
public class UDPListener implements Runnable {
    private final int port;
    private final BlockingQueue<UDPMessage> messageQueue;
    private DatagramSocket socket;
    private boolean running = true;
    
    /**
     * Creates a new UDPListener
     * @param port The port to listen on
     * @param messageQueue The queue to add messages to
     */
    public UDPListener(int port, BlockingQueue<UDPMessage> messageQueue) {
        this.port = port;
        this.messageQueue = messageQueue;
    }
    
    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            System.out.println("UDP listener started on port " + port);
            byte[] buffer = new byte[1024];
            
            while (running) {
                try {
                    // Receive packet
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    // Process packet
                    String message = new String(packet.getData(), 0, packet.getLength());
                    processMessage(message, packet);
                    
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error receiving UDP packet: " + e.getMessage());
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error creating UDP socket: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
    
    /**
     * Processes a UDP message and adds it to the queue
     * @param message The message content
     * @param packet The original packet (for source address info)
     */
    private void processMessage(String message, DatagramPacket packet) {
        try {
            // Parse the message
            // Expected format: TYPE:ClientID:QuestionNumber
            String[] parts = message.split(":");
            
            if (parts.length >= 3 && parts[0].equals("BUZZ")) {
                String clientId = parts[1];
                int questionNumber = Integer.parseInt(parts[2]);
                
                // Create UDPMessage object
                UDPMessage udpMessage = new UDPMessage(
                    parts[0], 
                    clientId, 
                    questionNumber,
                    packet.getAddress(),
                    packet.getPort()
                );
                
                // Add to queue
                messageQueue.put(udpMessage);
                
                System.out.println("Received UDP message: " + message + 
                                   " from " + packet.getAddress().getHostAddress() + 
                                   ":" + packet.getPort());
            }
        } catch (Exception e) {
            System.err.println("Error processing UDP message: " + e.getMessage());
        }
    }
    
    /**
     * Stops the UDP listener
     */
    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}