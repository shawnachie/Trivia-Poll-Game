import java.net.InetAddress;

/**
 * Represents a UDP message received from a client
 */
public class UDPMessage {
    private final String type;
    private final String clientId;
    private final int questionNumber;
    private final InetAddress sourceAddress;
    private final int sourcePort;
    private final long timestamp;
    
    /**
     * Creates a new UDPMessage
     * @param type The message type (e.g., "BUZZ")
     * @param clientId The client ID
     * @param questionNumber The question number
     * @param sourceAddress The source IP address
     * @param sourcePort The source port
     */
    public UDPMessage(String type, String clientId, int questionNumber, 
                      InetAddress sourceAddress, int sourcePort) {
        this.type = type;
        this.clientId = clientId;
        this.questionNumber = questionNumber;
        this.sourceAddress = sourceAddress;
        this.sourcePort = sourcePort;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Gets the message type
     */
    public String getType() {
        return type;
    }
    
    /**
     * Gets the client ID
     */
    public String getClientId() {
        return clientId;
    }
    
    /**
     * Gets the question number
     */
    public int getQuestionNumber() {
        return questionNumber;
    }
    
    /**
     * Gets the source address
     */
    public InetAddress getSourceAddress() {
        return sourceAddress;
    }
    
    /**
     * Gets the source port
     */
    public int getSourcePort() {
        return sourcePort;
    }
    
    /**
     * Gets the timestamp when the message was received
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "UDPMessage{" +
               "type='" + type + '\'' +
               ", clientId='" + clientId + '\'' +
               ", questionNumber=" + questionNumber +
               ", sourceAddress=" + sourceAddress +
               ", sourcePort=" + sourcePort +
               ", timestamp=" + timestamp +
               '}';
    }
}