import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class TriviaClient {
    // Network configuration
    private static final String DEFAULT_SERVER_ADDRESS = "localhost";
    private static final int TCP_PORT = 8080;
    private static final int UDP_PORT = 8081;
    private static final int CONNECTION_TIMEOUT = 5000; // 5 seconds
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    
    // Client state
    private String clientId;
    private String serverAddress;
    private int currentQuestion = 0;
    private int score = 0;
    private AtomicBoolean canAnswer = new AtomicBoolean(false);
    
    // Network components
    private Socket tcpSocket;
    private DatagramSocket udpSocket;
    private BufferedReader inFromServer;
    private PrintWriter outToServer;
    
    // GUI reference
    private ClientWindow gui;
    
    /**
     * Constructs a new TriviaClient with a specific client ID
     * @param clientId The unique identifier for this client
     * @param serverAddress The server address to connect to (null for default)
     */
    public TriviaClient(String clientId, String serverAddress) {
        this.clientId = clientId;
        this.serverAddress = serverAddress != null ? serverAddress : DEFAULT_SERVER_ADDRESS;
        
        System.out.println("DEBUG - Creating client with ID: " + clientId + " connecting to " + this.serverAddress);
        
        // Create GUI first, not on EDT to ensure it's ready
        this.gui = new ClientWindow();
        this.gui.setClient(this);
        
        connectToServer();
    }
    
    /**
     * Attempts to connect to the server with retry logic
     */
    private void connectToServer() {
        int attempts = 0;
        while (attempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                // Initialize TCP connection with timeout
                System.out.println("DEBUG - Connecting to server at " + serverAddress + ":" + TCP_PORT + 
                                 " (attempt " + (attempts + 1) + ")");
                
                tcpSocket = new Socket();
                tcpSocket.connect(new InetSocketAddress(serverAddress, TCP_PORT), CONNECTION_TIMEOUT);
                inFromServer = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                outToServer = new PrintWriter(tcpSocket.getOutputStream(), true);
                
                // Initialize UDP socket
                udpSocket = new DatagramSocket();
                
                // Send initial connection message with client ID
                outToServer.println("CONNECT:" + clientId);
                System.out.println("DEBUG - Connection successful, sent CONNECT message");
                
                // Start TCP listener thread
                System.out.println("DEBUG - Starting TCP message listener thread");
                new Thread(this::receiveTCPMessages).start();
                
                return; // Successfully connected
                
            } catch (IOException e) {
                attempts++;
                System.err.println("Error connecting to server (attempt " + attempts + "): " + e.getMessage());
                
                if (attempts < MAX_RECONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(2000); // Wait 2 seconds before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    JOptionPane.showMessageDialog(null, 
                        "Failed to connect to server after " + MAX_RECONNECT_ATTEMPTS + " attempts.\n" +
                        "Please check if the server is running and try again.", 
                        "Connection Error", 
                        JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                }
            }
        }
    }
    
    /**
     * Continuously listens for TCP messages from the server
     */
    private void receiveTCPMessages() {
        try {
            System.out.println("DEBUG - TCP listener thread started");
            String message;
            while ((message = inFromServer.readLine()) != null) {
                final String finalMessage = message;
                System.out.println("DEBUG - Received raw message: " + finalMessage);
                
                // Process message immediately
                processServerMessage(finalMessage);
            }
        } catch (IOException e) {
            System.err.println("TCP connection lost: " + e.getMessage());
            JOptionPane.showMessageDialog(null, 
                "Lost connection to server: " + e.getMessage(), 
                "Connection Error", 
                JOptionPane.ERROR_MESSAGE);
        } finally {
            closeConnections();
        }
    }
    
    /**
     * Processes messages received from the server
     * @param message The message from the server
     */
    private void processServerMessage(String message) {
        System.out.println("DEBUG - Processing message: " + message);
        
        if (message.startsWith("QUESTION:")) {
            System.out.println("DEBUG - Received question message");
            // Format: QUESTION:questionNumber:questionText:option1:option2:option3:option4
            String[] parts = message.split(":", 7);
            System.out.println("DEBUG - Split into " + parts.length + " parts");
            
            if (parts.length >= 7) {
                currentQuestion = Integer.parseInt(parts[1]);
                String questionText = parts[2];
                String[] options = new String[4];
                for (int i = 0; i < 4; i++) {
                    options[i] = parts[i + 3];
                }
                
                System.out.println("DEBUG - Question #" + currentQuestion + ": " + questionText);
                System.out.println("DEBUG - Options: " + String.join(", ", options));
                
                // Update GUI with new question (should be done on EDT)
                updateQuestion(currentQuestion, questionText, options);
                
                // Start poll phase - enable poll button, disable submit button and options
                setPollPhase(true);
                System.out.println("DEBUG - Poll phase started for question " + currentQuestion);
            } else {
                System.out.println("DEBUG - Malformed question message: not enough parts");
            }
        } else if (message.equals("ACK")) {
            System.out.println("DEBUG - Received ACK message");
            // We're allowed to answer the question
            canAnswer.set(true);
            
            // Update GUI - disable poll, enable options and submit
            setAnswerPhase(true);
            
        } else if (message.equals("NACK")) {
            System.out.println("DEBUG - Received NACK message");
            // We weren't the first to buzz in
            canAnswer.set(false);
            
            // Update GUI - disable poll, keep options and submit disabled
            setPollPhase(false);
            
        } else if (message.startsWith("CORRECT")) {
            System.out.println("DEBUG - Received CORRECT message");
            // Correct answer, update score
            score += 10;
            updateScore(score);
            canAnswer.set(false);
            
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, 
                    "Correct answer! +10 points", 
                    "Result", 
                    JOptionPane.INFORMATION_MESSAGE);
            });
            
        } else if (message.startsWith("WRONG")) {
            System.out.println("DEBUG - Received WRONG message");
            // Wrong answer, update score
            score -= 10;
            updateScore(score);
            canAnswer.set(false);
            
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, 
                    "Wrong answer! -10 points", 
                    "Result", 
                    JOptionPane.ERROR_MESSAGE);
            });
            
        } else if (message.equals("TIMEOUT")) {
            System.out.println("DEBUG - Received TIMEOUT message");
            // Didn't answer in time, penalize
            score -= 20;
            updateScore(score);
            canAnswer.set(false);
            
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, 
                    "Time's up! -20 points", 
                    "Timeout", 
                    JOptionPane.WARNING_MESSAGE);
            });
            
        } else if (message.equals("NEXT")) {
            System.out.println("DEBUG - Received NEXT message");
            // Moving to next question without scoring
            // GUI will be updated when next question arrives
            
        } else if (message.startsWith("FINAL:")) {
            System.out.println("DEBUG - Received FINAL message");
            // Game over, display final results
            String finalMessage = message.substring(6);
            showFinalResults(finalMessage);
            
        } else if (message.equals("KILL")) {
            System.out.println("DEBUG - Received KILL message");
            // Server wants to terminate this client
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, 
                    "Server has terminated the game", 
                    "Game Over", 
                    JOptionPane.INFORMATION_MESSAGE);
            });
            closeConnections();
            System.exit(0);
        } else {
            System.out.println("DEBUG - Received unknown message type: " + message);
        }
    }
    
    /**
     * Sends a UDP "buzz" message to the server
     */
    public void sendBuzz() {
        try {
            String buzzMessage = "BUZZ:" + clientId + ":" + currentQuestion;
            byte[] sendData = buzzMessage.getBytes();
            
            InetAddress serverAddress = InetAddress.getByName(this.serverAddress);
            DatagramPacket sendPacket = new DatagramPacket(
                sendData, sendData.length, serverAddress, UDP_PORT);
            
            udpSocket.send(sendPacket);
            System.out.println("DEBUG - Sent buzz message: " + buzzMessage);
            
        } catch (IOException e) {
            System.err.println("Error sending UDP buzz: " + e.getMessage());
        }
    }
    
    /**
     * Sends the selected answer to the server
     * @param selectedOption The index of the selected option (0-3)
     */
    public void submitAnswer(int selectedOption) {
        if (canAnswer.get()) {
            System.out.println("DEBUG - Submitting answer: " + selectedOption);
            outToServer.println("ANSWER:" + currentQuestion + ":" + selectedOption);
        } else {
            System.out.println("DEBUG - Cannot submit answer, not allowed");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, 
                    "You can't submit an answer at this time", 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
            });
        }
    }
    
    /**
     * Updates the GUI with a new question
     * Must be called on the Event Dispatch Thread
     */
    private void updateQuestion(int questionNumber, String questionText, String[] options) {
        System.out.println("DEBUG - Updating question in GUI");
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.setQuestion("Q" + questionNumber + ". " + questionText);
                for (int i = 0; i < options.length; i++) {
                    gui.setOption(i, options[i]);
                }
                System.out.println("DEBUG - Question updated in GUI");
            } else {
                System.out.println("DEBUG - Error: GUI is null");
            }
        });
    }
    
    /**
     * Updates the score display on the GUI
     */
    private void updateScore(int newScore) {
        System.out.println("DEBUG - Updating score to: " + newScore);
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.setScore("Score: " + newScore);
            }
        });
    }
    
    /**
     * Sets up the GUI for the polling phase
     * @param enabled Whether the poll button should be enabled
     */
    private void setPollPhase(boolean enabled) {
        System.out.println("DEBUG - Setting poll phase, enabled: " + enabled);
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.setPollEnabled(enabled);
                gui.setSubmitEnabled(false);
                gui.setOptionsEnabled(false);
                gui.startTimer(15); // 15 second timer for polling phase
                System.out.println("DEBUG - Poll phase set, timer started with 15 seconds");
            } else {
                System.out.println("DEBUG - Error: GUI is null when setting poll phase");
            }
        });
    }
    
    /**
     * Sets up the GUI for the answering phase
     * @param enabled Whether the answer submission should be enabled
     */
    private void setAnswerPhase(boolean enabled) {
        System.out.println("DEBUG - Setting answer phase, enabled: " + enabled);
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.setPollEnabled(false);
                gui.setSubmitEnabled(enabled);
                gui.setOptionsEnabled(enabled);
                gui.startTimer(10); // 10 second timer for answer phase
                System.out.println("DEBUG - Answer phase set, timer started with 10 seconds");
            } else {
                System.out.println("DEBUG - Error: GUI is null when setting answer phase");
            }
        });
    }
    
    /**
     * Displays the final results of the game
     */
    private void showFinalResults(String message) {
        System.out.println("DEBUG - Showing final results");
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.showFinalResults(message);
            }
        });
    }
    
    /**
     * Closes all network connections
     */
    private void closeConnections() {
        System.out.println("DEBUG - Closing network connections");
        try {
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
            }
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing connections: " + e.getMessage());
        }
    }
    
    /**
     * Main method to start the client
     */
    public static void main(String[] args) {
        // Generate a client ID or get from command line
        String clientId = (args.length > 0) ? args[0] : "Client-" + System.currentTimeMillis() % 1000;
        System.out.println("DEBUG - Starting client with ID: " + clientId);
        new TriviaClient(clientId, null);
    }
}