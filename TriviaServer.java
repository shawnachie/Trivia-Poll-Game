import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main server class for the Trivia Game
 * Manages the game state, client connections, and question flow
 */
public class TriviaServer {
    private static final int TCP_PORT = 8080;
    private static final int UDP_PORT = 8081;
    private static final int TOTAL_QUESTIONS = 20;
    private static final int POLL_TIME = 15; // seconds
    private static final int ANSWER_TIME = 10; // seconds
    
    private ServerSocket serverSocket;
    private List<ClientHandler> clientHandlers;
    private Map<Integer, String> questions;
    private Map<Integer, List<String>> options;
    private Map<Integer, Integer> answers; // question number -> correct answer index
    private volatile int currentQuestion;
    private volatile boolean gameInProgress;
    private BlockingQueue<UDPMessage> messageQueue;
    private Map<String, Integer> scores;
    private Timer questionTimer;
    private String currentAnswerer = null; // Tracks which client is answering
    private Timer pollTimeoutTimer = null; // Timer to handle poll timeout
    
    public TriviaServer() {
        this.clientHandlers = Collections.synchronizedList(new ArrayList<>());
        this.questions = new HashMap<>();
        this.options = new HashMap<>();
        this.answers = new HashMap<>();
        this.currentQuestion = 0;
        this.gameInProgress = false;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.scores = new ConcurrentHashMap<>();
        
        loadQuestions();
    }
    
    private void loadQuestions() {
        Properties props = new Properties();
        File propertiesFile = new File("trivia.properties");
        
        if (!propertiesFile.exists()) {
            System.err.println("Error: trivia.properties file not found in: " + propertiesFile.getAbsolutePath());
            System.exit(1);
        }
        
        try (InputStream input = new FileInputStream(propertiesFile)) {
            props.load(input);
            System.out.println("Successfully loaded properties file");
            
            // Clear existing questions
            questions.clear();
            options.clear();
            answers.clear();
            
            // Load questions from properties
            int loadedQuestions = 0;
            for (int i = 1; i <= TOTAL_QUESTIONS; i++) {
                String key = "question." + i;
                String value = props.getProperty(key);
                if (value != null) {
                    String[] parts = value.split("\\|");
                    if (parts.length == 6) {
                        questions.put(i, parts[0]);
                        
                        List<String> opts = new ArrayList<>();
                        for (int j = 1; j < 5; j++) {
                            opts.add(parts[j]);
                        }
                        options.put(i, opts);
                        
                        try {
                            int correctAnswer = Integer.parseInt(parts[5]);
                            if (correctAnswer < 0 || correctAnswer > 3) {
                                System.err.println("Warning: Invalid correct answer index for question " + i + 
                                                 ". Must be between 0 and 3.");
                                continue;
                            }
                            answers.put(i, correctAnswer);
                            loadedQuestions++;
                        } catch (NumberFormatException e) {
                            System.err.println("Warning: Invalid number format for correct answer in question " + i);
                        }
                    } else {
                        System.err.println("Warning: Invalid format for question " + i + 
                                         ". Expected 6 parts, got " + parts.length);
                    }
                } else {
                    System.err.println("Warning: Missing question " + i);
                }
            }
            
            if (loadedQuestions == 0) {
                System.err.println("Error: No valid questions loaded from properties file!");
                System.exit(1);
            }
            
            System.out.println("Successfully loaded " + loadedQuestions + " questions");
            
        } catch (IOException e) {
            System.err.println("Error loading questions from properties file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void start() {
        try {
            serverSocket = new ServerSocket(TCP_PORT);
            System.out.println("Server started on port " + TCP_PORT);
            
            // Start UDP listener thread
            UDPListener udpListener = new UDPListener(UDP_PORT, messageQueue);
            new Thread(udpListener).start();
            
            // Start the game manager thread
            GameManager gameManager = new GameManager();
            new Thread(gameManager).start();
            
            // Accept client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress().getHostAddress());
                
                // Generate client ID (could be more sophisticated in production)
                String clientId = "Client-" + clientHandlers.size();
                
                // Initialize score for new client
                scores.put(clientId, 0);
                
                // Create and start a new client handler
                ClientHandler clientHandler = new ClientHandler(clientSocket, clientId);
                clientHandlers.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
        }
    }
    
    /**
     * Sends a message to all connected clients
     */
    private void broadcastMessage(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                handler.sendMessage(message);
            }
        }
    }
    
    /**
     * Sends a message to a specific client by ID
     */
    private void sendMessageToClient(String clientId, String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.getClientId().equals(clientId)) {
                    handler.sendMessage(message);
                    return;
                }
            }
        }
    }
    
    /**
     * Processes a client answer and updates scores accordingly
     */
    private void processAnswer(String clientId, int questionNumber, int answerIndex) {
        // Ignore if not the current answerer
        if (!clientId.equals(currentAnswerer)) {
            return;
        }
        
        // Reset current answerer
        currentAnswerer = null;
        
        // Check if answer is correct
        Integer correctAnswer = answers.get(questionNumber);
        if (correctAnswer != null && correctAnswer == answerIndex) {
            // Correct answer, award points
            scores.put(clientId, scores.get(clientId) + 10);
            sendMessageToClient(clientId, "CORRECT");
            System.out.println("Client " + clientId + " answered correctly");
        } else {
            // Wrong answer, deduct points
            scores.put(clientId, scores.get(clientId) - 10);
            sendMessageToClient(clientId, "WRONG");
            System.out.println("Client " + clientId + " answered incorrectly");
        }
        
        // Cancel poll timeout timer if it's running
        if (pollTimeoutTimer != null) {
            pollTimeoutTimer.cancel();
            pollTimeoutTimer = null;
        }
        
        // Move to next question after a short delay
        Timer nextQuestionTimer = new Timer();
        nextQuestionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (this) {
                    if (currentQuestion < TOTAL_QUESTIONS) {
                        moveToNextQuestion();
                    } else {
                        endGame();
                    }
                }
            }
        }, 2000);
    }
    
    /**
     * Handles a timeout for the current answerer
     */
    private void handleAnswerTimeout() {
        if (currentAnswerer != null) {
            // Client didn't answer in time, penalize
            scores.put(currentAnswerer, scores.get(currentAnswerer) - 20);
            sendMessageToClient(currentAnswerer, "TIMEOUT");
            System.out.println("Client " + currentAnswerer + " timed out");
            
            // Reset current answerer
            currentAnswerer = null;
            
            // Move to next question
            if (currentQuestion < TOTAL_QUESTIONS) {
                moveToNextQuestion();
            } else {
                endGame();
            }
        }
    }
    
    /**
     * Handles the case where no client has polled within the time limit
     */
    private void handlePollTimeout() {
        System.out.println("Poll time expired with no polls. Moving to next question.");
        
        // No one has polled, so broadcast NEXT and move to next question
        broadcastMessage("NEXT");
        
        // Reset current answerer just in case
        currentAnswerer = null;
        
        // Move to next question
        if (currentQuestion < TOTAL_QUESTIONS) {
            moveToNextQuestion();
        } else {
            endGame();
        }
    }
    
    /**
     * Moves to the next question in the game
     */
    private void moveToNextQuestion() {
        // Cancel any existing poll timeout timer
        if (pollTimeoutTimer != null) {
            pollTimeoutTimer.cancel();
        }
        
        currentQuestion++;
        if (currentQuestion <= TOTAL_QUESTIONS) {
            String question = questions.get(currentQuestion);
            List<String> optionsList = options.get(currentQuestion);
            
            // Print correct answer for testing purposes
            System.out.println("Question " + currentQuestion + " correct answer: " + 
                              answers.get(currentQuestion) + " (Option " + 
                              optionsList.get(answers.get(currentQuestion)) + ")");
            
            // Format: QUESTION:questionNumber:questionText:option1:option2:option3:option4
            StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append("QUESTION:").append(currentQuestion).append(":");
            messageBuilder.append(question).append(":");
            for (String option : optionsList) {
                messageBuilder.append(option).append(":");
            }
            
            // Remove the trailing colon
            String message = messageBuilder.substring(0, messageBuilder.length() - 1);
            
            // Broadcast question to all clients
            broadcastMessage(message);
            System.out.println("Sent question " + currentQuestion + " to all clients");
            
            // Reset for new buzzer round
            currentAnswerer = null;
            
            // Set up poll timeout timer
            pollTimeoutTimer = new Timer();
            pollTimeoutTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    handlePollTimeout();
                }
            }, POLL_TIME * 1000); // Poll time in milliseconds
        }
    }
    
    /**
     * Ends the game and broadcasts final scores
     */
    private void endGame() {
        gameInProgress = false;
        
        // Cancel any running timers
        if (pollTimeoutTimer != null) {
            pollTimeoutTimer.cancel();
            pollTimeoutTimer = null;
        }
        
        // Find winner
        String winner = null;
        int highestScore = Integer.MIN_VALUE;
        
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > highestScore) {
                highestScore = entry.getValue();
                winner = entry.getKey();
            }
        }
        
        // Create final scores message
        StringBuilder finalMessage = new StringBuilder();
        finalMessage.append("FINAL:Game Over! ");
        
        if (winner != null) {
            finalMessage.append("Winner: ").append(winner)
                       .append(" with ").append(highestScore).append(" points.\n\n");
        }
        
        finalMessage.append("Final Scores:\n");
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            finalMessage.append(entry.getKey()).append(": ")
                       .append(entry.getValue()).append(" points\n");
        }
        
        // Broadcast final message
        broadcastMessage(finalMessage.toString());
        System.out.println("Game ended. Winner: " + winner);
        
        // Wait a bit and then shutdown
        Timer shutdownTimer = new Timer();
        shutdownTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                shutdown();
            }
        }, 10000);
    }
    
    /**
     * Gracefully shuts down the server
     */
    private void shutdown() {
        try {
            // Send kill message to all clients
            broadcastMessage("KILL");
            
            // Close all connections
            for (ClientHandler handler : clientHandlers) {
                handler.close();
            }
            
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            System.out.println("Server shutdown complete");
            System.exit(0);
        } catch (IOException e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
    }
    
    /**
     * GameManager class to control the game flow
     */
    private class GameManager implements Runnable {
        @Override
        public void run() {
            // Wait for at least one client to connect
            while (clientHandlers.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            
            // Start the game
            System.out.println("Starting game...");
            gameInProgress = true;
            moveToNextQuestion();
            
            // Main game loop
            while (gameInProgress) {
                try {
                    // Check for UDP messages (buzzes)
                    if (currentAnswerer == null) {
                        UDPMessage message = messageQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (message != null && message.getType().equals("BUZZ")) {
                            // Check if message is for current question
                            int questionNumber = message.getQuestionNumber();
                            if (questionNumber == currentQuestion) {
                                String clientId = message.getClientId();
                                
                                // This client gets to answer
                                currentAnswerer = clientId;
                                
                                // Send ACK to this client
                                sendMessageToClient(clientId, "ACK");
                                
                                // Send NACK to all other clients
                                synchronized (clientHandlers) {
                                    for (ClientHandler handler : clientHandlers) {
                                        if (!handler.getClientId().equals(clientId)) {
                                            handler.sendMessage("NACK");
                                        }
                                    }
                                }
                                
                                // Cancel poll timeout timer since someone has buzzed in
                                if (pollTimeoutTimer != null) {
                                    pollTimeoutTimer.cancel();
                                    pollTimeoutTimer = null;
                                }
                                
                                // Start answer timeout
                                Timer answerTimer = new Timer();
                                answerTimer.schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        handleAnswerTimeout();
                                    }
                                }, ANSWER_TIME * 1000);
                            }
                        }
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    /**
     * ClientHandler to manage TCP communication with each client
     */
    private class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String clientId;
        private boolean connected = true;
        
        public ClientHandler(Socket socket, String clientId) {
            this.socket = socket;
            this.clientId = clientId;
            try {
                this.out = new PrintWriter(socket.getOutputStream(), true);
                this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            } catch (IOException e) {
                System.err.println("Error creating client handler: " + e.getMessage());
                connected = false;
            }
        }
        
        @Override
        public void run() {
            try {
                // Process messages from client
                String message;
                while (connected && (message = in.readLine()) != null) {
                    processClientMessage(message);
                }
            } catch (IOException e) {
                System.err.println("Client disconnected: " + clientId);
            } finally {
                close();
                clientHandlers.remove(this);
                System.out.println("Client removed: " + clientId);
            }
        }
        
        /**
         * Processes messages from clients
         */
        private void processClientMessage(String message) {
            System.out.println("Received from client " + clientId + ": " + message);
            
            if (message.startsWith("CONNECT:")) {
                // Client is sending its ID
                String receivedId = message.substring(8);
                // Override our generated ID with the client's ID
                clientId = receivedId;
                System.out.println("Client ID set to: " + clientId);
                
                // Initialize score if not already present
                scores.putIfAbsent(clientId, 0);
                
            } else if (message.startsWith("ANSWER:")) {
                // Format: ANSWER:questionNumber:optionIndex
                String[] parts = message.split(":");
                if (parts.length >= 3) {
                    int questionNumber = Integer.parseInt(parts[1]);
                    int optionIndex = Integer.parseInt(parts[2]);
                    
                    // Process the answer
                    processAnswer(clientId, questionNumber, optionIndex);
                }
            }
        }
        
        /**
         * Sends a message to this client
         */
        public void sendMessage(String message) {
            if (connected && out != null) {
                out.println(message);
            }
        }
        
        /**
         * Gets the client ID
         */
        public String getClientId() {
            return clientId;
        }
        
        /**
         * Closes the connection to this client
         */
        public void close() {
            try {
                connected = false;
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing client handler: " + e.getMessage());
            }
        }
    }
    
    /**
     * Main method to start the server
     */
    public static void main(String[] args) {
        TriviaServer server = new TriviaServer();
        server.start();
    }
}